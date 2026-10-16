//go:build !client_version_report

package mobile

// publishClientIdentity assigns the wrapper identity that version.Report
// sends to the Panel.
//
// The pinned LadderAirport commit on merged main does not export
// version.ClientType or version.ClientVersion yet (added in LadderAirport#4,
// not merged). Referencing those names here would fail the AAR build.
// make aar adds the client_version_report tag once version.go declares
// `var ClientType`, and client_identity_report.go performs the assignment.
func publishClientIdentity(string) {}
