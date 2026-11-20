package backend

import (
	"encoding/json"
	"errors"
	"unicode/utf8"

	"phonepcbridge/protocol"
)

type reasoningOption struct {
	Effort      string `json:"reasoningEffort"`
	Description string `json:"description,omitempty"`
}

type serviceTierOption struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
}

type modelOption struct {
	ID                     string              `json:"id"`
	Model                  string              `json:"model"`
	DisplayName            string              `json:"displayName,omitempty"`
	Description            string              `json:"description,omitempty"`
	Hidden                 *bool               `json:"hidden,omitempty"`
	DefaultReasoningEffort string              `json:"defaultReasoningEffort,omitempty"`
	Efforts                []reasoningOption   `json:"supportedReasoningEfforts"`
	InputModalities        []string            `json:"inputModalities"`
	SupportsPersonality    *bool               `json:"supportsPersonality,omitempty"`
	IsDefault              *bool               `json:"isDefault,omitempty"`
	Upgrade                *string             `json:"upgrade,omitempty"`
	ServiceTiers           []serviceTierOption `json:"serviceTiers"`
	DefaultServiceTier     *string             `json:"defaultServiceTier"`
}

type modelCatalogPage struct {
	Data       []modelOption `json:"data"`
	NextCursor *string       `json:"nextCursor"`
}

// Native catalogs may grow fields; only explicit public fields cross the app boundary.
func catalogObject(raw json.RawMessage, names ...string) (map[string]json.RawMessage, error) {
	var source map[string]json.RawMessage
	if json.Unmarshal(raw, &source) != nil || source == nil {
		return nil, errors.New("invalid_native_catalog_object")
	}
	result := map[string]json.RawMessage{}
	for _, name := range names {
		if value, ok := source[name]; ok {
			result[name] = value
		}
	}
	return result, nil
}

func catalogRows(raw json.RawMessage, cursor string, limit int) ([]json.RawMessage, *string, error) {
	var page map[string]json.RawMessage
	var rows []json.RawMessage
	var next *string
	if appDecode(raw, &page) != nil || page == nil || json.Unmarshal(page["data"], &rows) != nil || rows == nil || len(rows) > limit || len(page["nextCursor"]) == 0 || json.Unmarshal(page["nextCursor"], &next) != nil {
		return nil, nil, errors.New("invalid_native_catalog_page")
	}
	if next != nil && (len(*next) > 4096 || (*next != "" && *next == cursor)) {
		return nil, nil, errors.New("invalid_native_catalog_cursor")
	}
	return rows, next, nil
}

func publicModelCatalog(raw json.RawMessage, cursor string) (modelCatalogPage, error) {
	rows, next, err := catalogRows(raw, cursor, 100)
	page := modelCatalogPage{Data: []modelOption{}, NextCursor: next}
	if err != nil {
		return page, err
	}
	models, ids := map[string]bool{}, map[string]bool{}
	for _, row := range rows {
		fields, err := catalogObject(row, "id", "model", "displayName", "description", "hidden", "defaultReasoningEffort", "supportedReasoningEfforts", "inputModalities", "supportsPersonality", "isDefault", "upgrade", "serviceTiers", "defaultServiceTier")
		if err != nil {
			return page, err
		}
		if value, ok := fields["supportedReasoningEfforts"]; ok {
			var options []json.RawMessage
			if json.Unmarshal(value, &options) != nil || len(options) > 100 {
				return page, errors.New("invalid_native_reasoning_catalog")
			}
			if options != nil {
				projected := []map[string]json.RawMessage{}
				for _, option := range options {
					fields, err := catalogObject(option, "reasoningEffort", "description")
					if err != nil {
						return page, err
					}
					projected = append(projected, fields)
				}
				fields["supportedReasoningEfforts"], _ = json.Marshal(projected)
			}
		}
		if value, ok := fields["serviceTiers"]; ok {
			var options []json.RawMessage
			if json.Unmarshal(value, &options) != nil || len(options) > 100 {
				return page, errors.New("invalid_native_service_tier_catalog")
			}
			if options != nil {
				projected := []map[string]json.RawMessage{}
				for _, option := range options {
					fields, err := catalogObject(option, "id", "name", "description")
					if err != nil {
						return page, err
					}
					var name, description *string
					if json.Unmarshal(fields["name"], &name) != nil || name == nil || json.Unmarshal(fields["description"], &description) != nil || description == nil {
						return page, errors.New("invalid_native_service_tier_option")
					}
					projected = append(projected, fields)
				}
				fields["serviceTiers"], _ = json.Marshal(projected)
			}
		}
		encoded, _ := json.Marshal(fields)
		var model modelOption
		if appDecode(encoded, &model) != nil || !appLabel(model.Model) || len(model.Model) > 128 || models[model.Model] {
			return page, errors.New("invalid_native_model")
		}
		if model.ID == "" {
			model.ID = model.Model
		}
		if !appLabel(model.ID) || len(model.ID) > 128 || ids[model.ID] {
			return page, errors.New("invalid_native_model_id")
		}
		efforts := map[string]bool{}
		for _, option := range model.Efforts {
			if !appLabel(option.Effort) || len(option.Effort) > 32 || efforts[option.Effort] {
				return page, errors.New("invalid_native_reasoning_option")
			}
			efforts[option.Effort] = true
		}
		if model.DefaultReasoningEffort != "" && (!appLabel(model.DefaultReasoningEffort) || len(model.DefaultReasoningEffort) > 32 || (model.Efforts != nil && !efforts[model.DefaultReasoningEffort])) {
			return page, errors.New("invalid_native_reasoning_default")
		}
		tiers := map[string]bool{}
		for _, option := range model.ServiceTiers {
			if option.ID == "" || !protocol.ValidServiceTier(option.ID) || tiers[option.ID] || !appLabel(option.Name) || len(option.Description) > 4096 || !utf8.ValidString(option.Description) {
				return page, errors.New("invalid_native_service_tier_option")
			}
			tiers[option.ID] = true
		}
		if model.DefaultServiceTier != nil && (*model.DefaultServiceTier == "" || !protocol.ValidServiceTier(*model.DefaultServiceTier) || (model.ServiceTiers != nil && !tiers[*model.DefaultServiceTier])) {
			return page, errors.New("invalid_native_service_tier_default")
		}
		if _, present := fields["inputModalities"]; !present {
			model.InputModalities = []string{"text", "image"}
		}
		if model.InputModalities == nil || len(model.InputModalities) > 100 {
			return page, errors.New("invalid_native_input_modalities")
		}
		modalities := map[string]bool{}
		for _, value := range model.InputModalities {
			if !appLabel(value) || len(value) > 32 || modalities[value] {
				return page, errors.New("invalid_native_input_modality")
			}
			modalities[value] = true
		}
		models[model.Model], ids[model.ID] = true, true
		page.Data = append(page.Data, model)
	}
	return page, nil
}
