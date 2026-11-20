package main

import (
	"golang.org/x/sys/windows"
	"testing"
)

func producerTestACL(t *testing.T, path string, public bool) {
	t.Helper()
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	text := "D:P(A;OICI;FA;;;" + user.User.Sid.String() + ")"
	if public {
		text += "(A;OICI;GR;;;BU)"
	}
	sd, err := windows.SecurityDescriptorFromString(text)
	if err != nil {
		t.Fatal(err)
	}
	acl, _, err := sd.DACL()
	if err != nil {
		t.Fatal(err)
	}
	if err = windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil); err != nil {
		t.Fatal(err)
	}
}
