# reelthyme

A Clojure library for building [realtime conversations](https://platform.openai.com/docs/guides/realtime-conversations) using
OpenAI's [Realtime API](https://platform.openai.com/docs/guides/realtime)

[![Clojars Project](https://img.shields.io/clojars/v/com.github.brianium/reelthyme.svg)](https://clojars.org/com.github.brianium/reelthyme)

## Todo

- [x] WebSocket transport for JVM
- [x] WebRTC transport for ClojureScript
- [ ] Acoustic Echo Cancellation (AEC) support for JVM

## Overview

reelthyme provides a simple, core.async-driven API for interacting with OpenAI's Realtime API. On the JVM it uses WebSocket, while ClojureScript will leverage WebRTC. Use it to build multimodal, real-time conversational experiences with minimal boilerplate.

## Key Concepts

- **connect!**: Opens a session channel for sending client events and receiving server events. On the JVM this uses WebSocket, while in ClojureScript it uses WebRTC and manages audio playback automatically.  
- **stream!** (JVM only): Splits incoming server events into a filtered event channel and an audio stop function.  
- **capture-audio!** (JVM only): Streams microphone input into the session as audio-buffer events.  
- **core.async channels**: All data flows via clojure.core.async channels and go blocks for composability.

## Basic Usage

Start a REPL and require the namespaces:

```clojure
(ns my-app.core
  (:require [reelthyme.core :as rt]
            [reelthyme.schema :as sc]
            [clojure.core.async :as a]))
```

### 1. Connect

Open a realtime session, with optional event validation:

```clojure
;;; JVM 
(def session-ch
  (rt/connect! {:xf-in (map sc/validate)})  ;; validate outgoing events
  
;;; ClojureScript
(def session-ch
  (rt/connect! client-secret {:xf-in (map sc/validate)})) ;;; client-secret is fetched from a server
```

`reelthyme.core/create-client-secret` is included as a convenient server-side function for creating client secrets fit for WebRTC.

On the JVM, authentication can be provided as an `:api-key` option, or the default will attempt to use
the `OPENAI_API_KEY` environment variable. WebRTC requires some work on your own server. See [Connection details](https://platform.openai.com/docs/guides/realtime-webrtc#creating-an-ephemeral-token) in the OpenAI WebRTC docs.

The `reelthyme.schema` namespace is completely optional and provides a malli compatible schema intended to aid development via instrumentation. See [example/validate.cljc](dev/example/validate.cljc) and [example/webrtc.cljs](dev/example/webrtc.cljs) for an example of dev instrumentation.

### 2. Stream Events

Separate audio deltas from other events (JVM):

```clojure
;;; Receive server events as plain Clojure maps
(let [[event-ch stop-audio] (rt/stream! session-ch)]
  (a/go-loop []
    (when-let [ev (a/<! event-ch)]
      (println "Server event:" ev)
      (recur))))
```

Or just read from the single session channel when using WebRTC:

```clojure
;;; Receive server events as plain Clojure maps
(a/go-loop []
  (when-let [ev (a/<! session-ch)]
    (println "Server event:" ev)
    (recur)))
```

### 3. Send Messages

Send client events as plain Clojure maps. 

```clojure
;; Send a text message
(a/put! session-ch
        {:type "conversation.item.create"
         :item {:type "message"
                :role "user"
                :content [{:type "input_text"
                           :text "What is the weather today?"}]}})

;; Request both text and audio response
(a/put! session-ch
        {:type "response.create"
         :response {:modalities ["text" "audio"]}})
```

### 4. Capture Microphone Audio

We must explicitly initiate audio capture in the JVM

```clojure
(def stop-capture
  (rt/capture-audio! session-ch {:interval-ms 500}))

;; Later, stop capturing
(stop-capture)
```

The ClojureScript version of `connect!` behaves a little differently because browser permissions are a bit more nuanced.

The `:content-types` param is a set that hints at the expected values belonging to the `[:content :type]` property of messages in a session.
It defaults to `#{"input_audio" "input_text"}` (supporting both audio and text inputs)

#### Method 1 - Begin capture on connect! automatically

``` clojure
(connect! client-secret) ;; Microphone access requested by default when connect! is called
(connect! client-secret {:content-types #{"input_audio"}}) ;; anticipate only audio input
```

If `:conntent-types` contains `"input_audio"` at all, the end user will be prompted for mic access automatically when `connect!` is called.

#### Method 2 - Manually provide a MediaStreamTrack

This method is useful if you want more control over getting access to user media. For example you may want play a ringtone or log some feedback before
calling `navigator.mediaDevices.getUserMedia()`.

``` clojure
(go
  (let [stream (<p! (.getUserMedia js/navigator.mediaDevices #js {:audio true}))
        track  (aget (.getAudioTracks stream) 0)]
    (connect! client-secret {:media-stream-track track})))
```

#### Text input AND output

It is possible to use text for input AND output by setting `:content-types` to `#{"input_text"}`.

## Sideband Channels

See [OpenAI docs](https://platform.openai.com/docs/guides/realtime-server-controls). A sideband channel allows a client side conversation to be followed on
the server. If using CLJS, a special event will be sent of type `"reelthyme.call_id"`. This event will have a `"call_id"` property that can be passed back to the server
and used via the websocket transport using the `:call_id` param.

``` clojure
(def session-ch (rt/connect! {:call-id "rtc_i_came_from_the_client" :api-key "same_client_secret_value_used_on_client"}))
```

This is fun and cool, because you can control the client from the backend (tool calls, store context, etc...)

## Example Workflow (JVM)

1. **connect!** opens and authenticates the channel.  
2. **stream!** routes audio to an audio thread and events to your go-loop.  
3. **capture-audio!** pushes live audio chunks into the session.  
4. Use **core.async** puts and takes to drive your application logic.

## Example Workflow (ClojureScript with WebRTC)

1. **connect!** opens an RTCPeerConnection over WebRTC, handles audio playback, and sets up a data channel for events.  
2. No need for **stream!** or **capture-audio!** — audio is managed natively via the browser's WebRTC APIs.  
3. Consume server events directly from the channel returned by `connect!`, which yields ClojureScript maps.  
4. Use **core.async** puts and takes to send client events or process server events.

## Differences between ClojureScript and Clojure

The ClojureScript version of connect! requires a session with an ephemeral client secret:

```clojure
;;; JVM
(connect! params)

;;; ClojureScript
(connect! client-secret params)
```

In the JVM version, you can specify `:model` or `:call-id` directly in params, but in CLJS, the `:model` property is specified when creating a client secret server side. `:call-id` is only supported using the websocket transport.

Also note, that for efficiency sake, server events do not go through `js->clj` by default. You can ad an `:xf-out` param to do this if you desire, but the default behavior is to use native JS objects for server events.

It should also be noted that browsers often impose restrictions on creating audio contexts of any kind without user interaction. This means that ClojureScript implementations will likely have to put connect! behind a user interaction (such as a click).

Check out the [example ClojureScript application](dev/example/webrtc.cljs). It might also be helpful to see the [example handler](dev/example/handler.clj) used to create client secrets. See [OpenAI docs on creating client secrets](https://platform.openai.com/docs/api-reference/realtime-sessions/create-realtime-client-secret).

## Note on Acoustic Echo Cancellation:

Acoustic Echo Cancellation (AEC) can be used to prevent awkward experiences where an agent hears itself and gets stuck in an infinite loop speaking to itself. AEC comes standard with WebRTC, so this issue should not surface when using it. If using the websocket transport on the JVM, you may have a better experience using a headset or disabling vads for a more "push to talk" approach.
