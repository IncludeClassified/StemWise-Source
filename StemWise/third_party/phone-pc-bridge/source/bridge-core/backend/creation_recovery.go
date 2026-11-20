package backend

import (
	"phonepcbridge/protocol"
	"strings"
)

func creationFailureCode(outcome protocol.AdapterReply, err error) string {
	if code := authRefusal(outcome, err); code != "" {
		return code
	}
	if (outcome.Code == "creation_not_sent" || outcome.Code == "creation_rejected" || outcome.Code == "creation_capacity_reached") && !creationDidNotStart(outcome, err) {
		return "creation_observation_unavailable"
	}
	if err == nil && strings.HasPrefix(outcome.Code, "creation_") {
		if _, known := appFailures[outcome.Code]; known {
			return outcome.Code
		}
	}
	return "creation_observation_unavailable"
}

func creationDidNotStart(outcome protocol.AdapterReply, err error) bool {
	return err == nil && outcome.State == "rejected" && len(outcome.Result) == 0 && (outcome.Code == "creation_not_sent" || outcome.Code == "creation_rejected" || outcome.Code == "creation_capacity_reached")
}
