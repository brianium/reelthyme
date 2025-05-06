# reelthyme

A Clojure library for building [realtime conversations](https://platform.openai.com/docs/guides/realtime-conversations) using
OpenAI's [Realtime API](https://platform.openai.com/docs/guides/realtime)

## Todo

- [x] WebSocket transport for JVM
- [ ] WebRTC transport for ClojureScript

## Overview

reelthyme provides a simple, core.async-driven API for interacting with OpenAI's Realtime API. On the JVM it uses WebSocket, while ClojureScript leverages WebRTC. Use it to build multimodal, real-time conversational experiences with minimal boilerplate.

## Key Concepts

- **connect!**: Open a session channel for sending client events and receiving server events.  
- **stream!**: Splits incoming server events into a filtered event channel and an audio stop function.  
- **capture-audio!**: Streams microphone input into the session as audio-buffer events.  
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
(def session-ch
  (rt/connect! {:xf-in (map sc/validate)})  ;; validate outgoing events
```

### 2. Stream Events

Separate audio deltas from other events:

```clojure
(def [event-ch stop-audio]
  (rt/stream! session-ch))

(a/go-loop []
  (when-let [ev (a/<! event-ch)]
    (println "Server event:" ev)
    (recur)))
```

### 3. Send Messages

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

```clojure
(def stop-capture
  (rt/capture-audio! session-ch {:interval-ms 500}))

;; Later, stop capturing
(stop-capture)
```

## Example Workflow

1. **connect!** opens and authenticates the channel.  
2. **stream!** routes audio to an audio thread and events to your go-loop.  
3. **capture-audio!** pushes live audio chunks into the session.  
4. Use **core.async** puts and takes to drive your application logic.  

## Next Steps

- Explore `reelthyme.schema` for built-in event validation.  
- Customize transports and logging via `:xf-in`, `:xf-out`, and `:ex-handler` options.  
- Build responsive UIs or backends that react to real-time OpenAI events.  
