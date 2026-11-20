package protocol

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"reflect"
	"strconv"
	"strings"
	"unicode/utf8"
)

func DecodeStrict(data []byte, value any) error {
	if !utf8.Valid(data) {
		return errors.New("invalid_encoding")
	}
	// encoding/json replaces unpaired UTF-16 escapes; reject them before owner text can change.
	quoted := false
	for index := 0; index < len(data); index++ {
		if !quoted {
			if data[index] == '"' {
				quoted = true
			}
			continue
		}
		if data[index] == '"' {
			quoted = false
			continue
		}
		if data[index] != '\\' {
			continue
		}
		index++
		if index >= len(data) {
			return errors.New("invalid_escape")
		}
		if data[index] != 'u' {
			continue
		}
		if index+4 >= len(data) {
			return errors.New("invalid_escape")
		}
		number, err := strconv.ParseUint(string(data[index+1:index+5]), 16, 16)
		if err != nil {
			return err
		}
		index += 4
		if number >= 0xd800 && number <= 0xdbff {
			if index+6 >= len(data) || data[index+1] != '\\' || data[index+2] != 'u' {
				return errors.New("unpaired_surrogate")
			}
			low, err := strconv.ParseUint(string(data[index+3:index+7]), 16, 16)
			if err != nil || low < 0xdc00 || low > 0xdfff {
				return errors.New("unpaired_surrogate")
			}
			index += 6
		} else if number >= 0xdc00 && number <= 0xdfff {
			return errors.New("unpaired_surrogate")
		}
	}
	d := json.NewDecoder(bytes.NewReader(data))
	var walk func(reflect.Type) error
	walk = func(schema reflect.Type) error {
		for schema != nil && schema.Kind() == reflect.Pointer {
			schema = schema.Elem()
		}
		if schema == reflect.TypeOf(json.RawMessage{}) {
			schema = nil
		}
		t, err := d.Token()
		if err != nil {
			return err
		}
		if delim, ok := t.(json.Delim); ok {
			if delim == '{' {
				seen := map[string]bool{}
				for d.More() {
					key, err := d.Token()
					if err != nil {
						return err
					}
					name, ok := key.(string)
					if !ok || seen[name] {
						return errors.New("duplicate_field")
					}
					seen[name] = true
					var child reflect.Type
					if schema != nil && schema.Kind() == reflect.Struct {
						// encoding/json accepts case-insensitive aliases; the wire contract does not.
						for _, field := range reflect.VisibleFields(schema) {
							if field.PkgPath != "" {
								continue
							}
							key, _, _ := strings.Cut(field.Tag.Get("json"), ",")
							if key == "" {
								key = field.Name
							}
							if key != "-" && key == name {
								child = field.Type
								break
							}
						}
						if child == nil {
							return errors.New("unknown_field")
						}
					} else if schema != nil && schema.Kind() == reflect.Map {
						child = schema.Elem()
					}
					if err = walk(child); err != nil {
						return err
					}
				}
				end, err := d.Token()
				if err != nil || end != json.Delim('}') {
					return errors.New("invalid_object")
				}
			} else if delim == '[' {
				var element reflect.Type
				if schema != nil && (schema.Kind() == reflect.Slice || schema.Kind() == reflect.Array) {
					element = schema.Elem()
				}
				for d.More() {
					if err := walk(element); err != nil {
						return err
					}
				}
				end, err := d.Token()
				if err != nil || end != json.Delim(']') {
					return errors.New("invalid_array")
				}
			} else {
				return errors.New("invalid_json")
			}
		}
		return nil
	}
	if err := walk(reflect.TypeOf(value)); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return errors.New("trailing_json")
	}
	d = json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	return d.Decode(value)
}
