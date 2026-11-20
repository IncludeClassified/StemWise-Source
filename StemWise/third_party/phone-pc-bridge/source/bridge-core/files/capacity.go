package files

import (
	"errors"
	"fmt"
	"os"
	"sync"
)

var ErrStorageCapacity = errors.New("storage_capacity_unavailable")

const StorageReserveInodes = 1024

// Budget shares conservative in-flight reservations between stores on one filesystem.
type Budget struct {
	mu                              sync.Mutex
	minimum, claimed, claimedInodes uint64
}

func NewBudget(minimumFreeBytes uint64) *Budget { return &Budget{minimum: minimumFreeBytes} }

func (b *Budget) reserve(root *os.Root, size int64) (func(), error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	directory, err := root.Open(".")
	if err != nil {
		return nil, fmt.Errorf("%w: free_space_unconfirmed: %v", ErrStorageCapacity, err)
	}
	available, inodes, err := availableSpace(directory)
	directory.Close()
	if err != nil {
		return nil, fmt.Errorf("%w: free_space_unconfirmed: %v", ErrStorageCapacity, err)
	}
	// Include directory/metadata allocation and the one-byte oversize check.
	required := uint64(size) + (64 << 10)
	// A new peer directory, staging directory, content and metadata need at most four inodes.
	const requiredInodes = 4
	bytesFit := available >= b.minimum && b.claimed <= available-b.minimum && required <= available-b.minimum-b.claimed
	inodesFit := inodes >= StorageReserveInodes && b.claimedInodes <= inodes-StorageReserveInodes && requiredInodes <= inodes-StorageReserveInodes-b.claimedInodes
	if !bytesFit || !inodesFit {
		return nil, fmt.Errorf("%w: available_bytes=%d reserve_bytes=%d active_bytes=%d required_bytes=%d available_inodes=%d reserve_inodes=%d active_inodes=%d required_inodes=%d", ErrStorageCapacity, available, b.minimum, b.claimed, required, inodes, StorageReserveInodes, b.claimedInodes, requiredInodes)
	}
	b.claimed += required
	b.claimedInodes += requiredInodes
	return func() {
		b.mu.Lock()
		b.claimed -= required
		b.claimedInodes -= requiredInodes
		b.mu.Unlock()
	}, nil
}
