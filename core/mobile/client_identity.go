package mobile

import "github.com/ladderairport/agent/internal/version"

// publishClientIdentity writes the Android wrapper identity into the version
// package. control.Server.Ping calls version.Report, which copies these vars
// onto the heartbeat. Version and Commit stay the core ldflags.
// LuciVersion is not an Android field.
func publishClientIdentity(clientVersion string) {
	version.ClientType = ClientTypeAndroid
	version.ClientVersion = clientVersion
}
