//go:build client_version_report

package mobile

import "github.com/ladderairport/agent/internal/version"

// publishClientIdentity writes the Android wrapper identity into the version
// package. control.Server.Ping calls version.Report, which reads these vars.
// Version and Commit stay the core ldflags; LuciVersion is not an Android field.
func publishClientIdentity(clientVersion string) {
	version.ClientType = ClientTypeAndroid
	version.ClientVersion = clientVersion
}
