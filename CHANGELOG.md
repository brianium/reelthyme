# Change Log

## [2025.12.07.1623] - 2025-12-07

### Added

**ClojureScript**

A new event of type `"reelthyme.call_id"` will be sent to WebRTC channels. This event is used to capture the call id of the session which can be used for sideband channels on the server.

**Clojure**

The `connect!` method no accepts a `:call-id` parameter that allows server side control of a client side conversation. If `:call-id` is given, `:model` is ignored. If using a `:call-id`, ensure that an `:api-key` is given using the `:value` of the client-secret generated for authenticating the client side channel.

### Changed

**BREAKING CHANGES**

- The WebRTC transport used via CLJS no longer uses `js->clj` by default. Native JS objects are the default. `js->clj` can still be applied by providing an `:xf-out` transducer.
- The websocket transport no longer supports a `:uri` param, and instead supports `:model` and `:call-id`. `:model` defaults to `gpt-realtime` but can be changed to `gpt-realtime-mini` for example.

## [2025.11.30.1359] - 2025-11-30

### Added

**ClojureScript Only**

A `:media-stream-track` param is now supported. Supersedes `:content-types` if provided. Useful for workflows
requiring more nuanced permission flows - for example calling `navigator.mediaDevices.getUserMedia()` manually
and then connecting only after permission is granted.

```clojure
(connect! client-secret-from-server {:media-stream-strack (custom-get-media-stream-track-some-how)})
```

### Changed

Demo now includes a checkbox for testing manual provision of a `MediaStreamTrack`

## [2025.11.29.1007] - 2025-11-29

### Changed

**BREAKING CHANGES**

This release migrates to Open AI's GA realtime models. The schemas and payload structures have had significant changes.
See [Open AI's Beta to GA migration guide](https://platform.openai.com/docs/guides/realtime#beta-to-ga-migration).

- [malli](https://github.com/metosin/malli) is no longer a hard dependency
- JSON handling for the JVM and demos switched from cheshire to [charred](https://github.com/cnuernber/charred)
- schema updated to account for GA realtime models
- JVM and WebRTC transports updated to account for GA
- Demo application now supports toggling content types with output modalities
  - The former `:modalities` key is now `:output_modalities` and supports either `["audio"]` or `["text"]` (but no longer both)
