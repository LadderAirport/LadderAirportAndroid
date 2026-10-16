package io.ladderairport.agent.service

internal const val CLIENT_TYPE_ANDROID = "android"

/**
 * JSON passed to the mobile Runner.
 *
 * [clientVersion] is the APK versionName (BuildConfig.VERSION_NAME). It is
 * sent as client_version and must not be written to the legacy "version" key,
 * which used to replace the core agent version baked into the AAR.
 * core_version stays the submodule `git describe --tags --always` value.
 */
internal fun buildRunnerConfig(
    panelUrl: String,
    nodeId: String,
    token: String,
    dataDir: String,
    reportSecs: Int,
    configSecs: Int,
    clientVersion: String,
): Map<String, Any> {
    return linkedMapOf(
        "panel_url" to panelUrl,
        "node_id" to nodeId,
        "token" to token,
        "data_dir" to dataDir,
        "report_secs" to reportSecs,
        "config_secs" to configSecs,
        "uplink_ws" to true,
        "client_version" to clientVersion,
        "client_type" to CLIENT_TYPE_ANDROID,
    )
}
