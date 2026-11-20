// Offline integration fixture for the pinned Bridge managed-file implementation.
package main

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"

	"phonepcbridge/files"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	source, scratch := os.Args[1], os.Args[2]
	input, err := os.Open(source)
	must(err)
	descriptor, err := files.DescribeReader("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", filepath.Base(source), input)
	must(err)
	_, err = input.Seek(0, io.SeekStart)
	must(err)
	store, err := files.Open(filepath.Join(scratch, "managed"))
	must(err)
	defer store.Close()
	queued, err := store.Put("fixture-peer", descriptor, input)
	must(err)
	must(input.Close())
	if queued != descriptor {
		panic("producer descriptor changed")
	}
	received, content, err := store.OpenVerified("fixture-peer", descriptor.ID)
	must(err)
	if received != descriptor {
		panic("consumer descriptor changed")
	}
	output, err := os.OpenFile(filepath.Join(scratch, "received.zip"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	must(err)
	_, err = io.Copy(output, content)
	must(err)
	must(content.Close())
	must(output.Sync())
	must(output.Close())
	must(store.Remove("fixture-peer", descriptor.ID))
	if _, err := store.Info("fixture-peer", descriptor.ID); !os.IsNotExist(err) {
		panic("managed transfer was not removed")
	}
	must(json.NewEncoder(os.Stdout).Encode(received))
}
