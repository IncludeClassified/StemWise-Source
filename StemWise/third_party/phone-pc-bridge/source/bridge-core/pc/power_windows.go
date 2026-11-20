package pc

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strconv"
	"unsafe"

	"golang.org/x/sys/windows"
)

type windowsPower struct{}

func platformPowerSystem() powerSystem { return windowsPower{} }

var powerInformation = windows.NewLazySystemDLL("powrprof.dll").NewProc("CallNtPowerInformation")
var interruptTime = windows.NewLazySystemDLL("kernelbase.dll").NewProc("QueryInterruptTime")
var adjustPowerPrivilege = windows.NewLazySystemDLL("advapi32.dll").NewProc("AdjustTokenPrivileges")

func powerBootID() (string, error) {
	// SYSTEM_BOOT_ENVIRONMENT_INFORMATION (SystemInformationClass90) contains
	// the kernel's boot GUID. It is independent of this host process and uptime.
	var environment struct {
		Identifier [16]byte
		Firmware   uint32
		Padding    uint32
		Flags      uint64
	}
	var returned uint32
	if err := windows.NtQuerySystemInformation(90, unsafe.Pointer(&environment), uint32(unsafe.Sizeof(environment)), &returned); err != nil || environment.Identifier == [16]byte{} {
		return "", errors.New("power_observation_unavailable")
	}
	return hex.EncodeToString(environment.Identifier[:]), nil
}

func lastWakeTime() (uint64, error) {
	if powerInformation.Find() != nil {
		return 0, errors.New("power_observation_unavailable")
	}
	var wake uint64
	status, _, _ := powerInformation.Call(14, 0, 0, uintptr(unsafe.Pointer(&wake)), unsafe.Sizeof(wake))
	if status != 0 {
		return 0, errors.New("power_observation_unavailable")
	}
	return wake, nil
}

func (windowsPower) Observe(ctx context.Context) (PowerObservation, error) {
	var result PowerObservation
	if ctx.Err() != nil || interruptTime.Find() != nil {
		return result, errors.New("power_observation_unavailable")
	}
	boot, err := powerBootID()
	if err != nil {
		return result, err
	}
	wake, err := lastWakeTime()
	if err != nil {
		return result, err
	}
	var current uint64
	interruptTime.Call(uintptr(unsafe.Pointer(&current)))
	result.BootID, result.UptimeMS = boot, windows.DurationSinceBoot().Milliseconds()
	again, err := lastWakeTime()
	bootAgain, bootErr := powerBootID()
	if err != nil || bootErr != nil || again != wake || bootAgain != boot || wake > current || ctx.Err() != nil {
		return PowerObservation{}, errors.New("power_observation_unavailable")
	}
	if wake != 0 {
		age := int64((current - wake) / 10000)
		result.WakeAgeMS = &age
	}
	digest := sha256.Sum256([]byte(boot + "\n" + strconv.FormatUint(wake, 10)))
	result.TransitionID = hex.EncodeToString(digest[:])
	if !result.Valid() {
		return PowerObservation{}, errors.New("power_observation_unavailable")
	}
	return result, nil
}

// Read assignment from this exact host token, without enabling the privilege.
// Presence permits an attempt; it does not prove Windows will complete shutdown.
func (windowsPower) ShutdownPrivilege(ctx context.Context) (bool, error) {
	if ctx.Err() != nil {
		return false, ctx.Err()
	}
	var token windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_QUERY, &token); err != nil {
		return false, errors.New("power_privilege_unavailable")
	}
	defer token.Close()
	name, _ := windows.UTF16PtrFromString("SeShutdownPrivilege")
	var target windows.LUID
	if windows.LookupPrivilegeValue(nil, name, &target) != nil {
		return false, errors.New("power_privilege_unavailable")
	}
	var size uint32
	if err := windows.GetTokenInformation(token, windows.TokenPrivileges, nil, 0, &size); err != windows.ERROR_INSUFFICIENT_BUFFER || size < 4 || size > 64<<10 {
		return false, errors.New("power_privilege_unavailable")
	}
	data := make([]byte, size)
	if err := windows.GetTokenInformation(token, windows.TokenPrivileges, &data[0], size, &size); err != nil {
		return false, errors.New("power_privilege_unavailable")
	}
	privileges := (*windows.Tokenprivileges)(unsafe.Pointer(&data[0]))
	offset := uint32(unsafe.Offsetof(privileges.Privileges))
	if size < offset || uint64(privileges.PrivilegeCount)*uint64(unsafe.Sizeof(windows.LUIDAndAttributes{})) > uint64(size-offset) {
		return false, errors.New("power_privilege_unavailable")
	}
	if ctx.Err() != nil {
		return false, ctx.Err()
	}
	for _, privilege := range privileges.AllPrivileges() {
		if privilege.Luid == target && privilege.Attributes&windows.SE_PRIVILEGE_REMOVED == 0 {
			return true, nil
		}
	}
	return false, nil
}

func (windowsPower) Shutdown(ctx context.Context) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	var token windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_ADJUST_PRIVILEGES|windows.TOKEN_QUERY, &token); err != nil {
		return errors.New("power_privilege_unavailable")
	}
	defer token.Close()
	name, _ := windows.UTF16PtrFromString("SeShutdownPrivilege")
	var luid windows.LUID
	if windows.LookupPrivilegeValue(nil, name, &luid) != nil {
		return errors.New("power_privilege_unavailable")
	}
	privileges := windows.Tokenprivileges{PrivilegeCount: 1}
	privileges.Privileges[0] = windows.LUIDAndAttributes{Luid: luid, Attributes: windows.SE_PRIVILEGE_ENABLED}
	var previous windows.Tokenprivileges
	var size uint32
	if adjustPowerPrivilege.Find() != nil {
		return errors.New("power_privilege_unavailable")
	}
	ok, _, privilegeErr := adjustPowerPrivilege.Call(uintptr(token), 0, uintptr(unsafe.Pointer(&privileges)), unsafe.Sizeof(previous), uintptr(unsafe.Pointer(&previous)), uintptr(unsafe.Pointer(&size)))
	if ok == 0 || privilegeErr == windows.ERROR_NOT_ALL_ASSIGNED {
		return errors.New("power_privilege_unavailable")
	}
	defer windows.AdjustTokenPrivileges(token, false, &previous, 0, nil, nil)
	if ctx.Err() != nil {
		return ctx.Err()
	}
	// Local planned power-off only: no force-close, restart, shell or arbitrary args.
	if err := windows.InitiateSystemShutdownEx(nil, nil, 0, false, false, 0x80040001); err != nil {
		return errors.New("power_shutdown_failed")
	}
	return nil
}
