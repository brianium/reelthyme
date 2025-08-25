(ns reelthyme.schema
  "A schema for client events sent to OpenAI"
  (:require [clojure.set :as set]
            [malli.core :as m]
            [malli.error :as me]))

;;; Client Events

(def Function
  [:map
   [:type [:enum "function"]]
   [:name :string]
   [:parameters {:description "A malli schema in vector format" :optional true} vector?]
   [:description {:optional true} :string]])

(def Voice
  [:enum "alloy" "ash" "ballad" "coral" "echo" "fable" "onyx" "nova" "sage" "shimmer" "verse"])

(def Role
  [:enum "user" "assistant" "system"])

(def AudioFormat
  [:enum "pcm16" "g711_ulaw" "g711_alaw"])

(def InputAudioNoiseReduction
  [:map
   [:type [:enum "near_field" "far_field"]]])

(def InputAudioTranscription
  [:map
   [:language {:description "ISO-639-1 (e.g. en)" :optional true} :string]
   [:model {:optional true} [:enum "gpt-4o-transcribe" "gpt-4o-mini-transcribe" "whisper-1"]]
   [:prompt {:optional true} :string]])

(def TurnDetection
  [:and
   [:map
    [:create_response {:optional true} :boolean]
    [:eagerness {:optional true} [:enum "low" "medium" "high" "auto"]]
    [:interrupt_response {:optional true} :boolean]
    [:prefix_padding_ms {:optional true} :int]
    [:silence_duration_ms {:optional true} :int]
    [:threshold {:optional true} number?]
    [:type [:enum "semantic_vad" "server_vad"]]]
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (if (= "semantic_vad" (:type value))
                       "semantic_vad only allows settings: eagerness"
                       "server_vad only allows settings: prefix_padding_ms, silence_duration_ms, threshold"))}
    (fn [{:keys [type] :as td}]
      (let [ks (set (keys td))
            server-only   #{:prefix_padding_ms :silence_duration_ms :threshold}
            semantic-only #{:eagerness}]
        (if (= type "semantic_vad")
          (= 0 (count (set/intersection server-only ks)))
          (= 0 (count (set/intersection semantic-only ks))))))]])

(def MaxResponseOutputTokens
  [:or :int [:enum "inf"]])

(def Modalities
  [:and
   [:vector {:min 1 :max 2} [:enum "audio" "text"]]
   [:fn {:error/message "Can be [\"audio\", \"text\"] or [\"text\"]"}
    (fn [items]
      (and
       (not= ["audio"] items)
       (= (count items) (count (set items)))))]])

(def ToolChoice
  [:or
   [:enum "auto" "none" "required"]
   Function])

(def Session
  [:map
   [:input_audio_format {:optional true} AudioFormat]
   [:input_audio_noise_reduction {:optional true} InputAudioNoiseReduction]
   [:input_audio_transcription {:optional true} InputAudioTranscription]
   [:instructions {:optional true} :string]
   [:max_response_output_tokens {:optional true} MaxResponseOutputTokens]
   [:modalities {:optional true} Modalities]
   [:model {:optional true} :string]
   [:output_audio_format {:optional true} AudioFormat]
   [:temperature {:optional true} number?]
   [:tool_choice {:optional true} ToolChoice]
   [:tools {:optional true} [:vector Function]]
   [:turn_detection {:optional true} [:maybe TurnDetection]]
   [:voice {:optional true} Voice]])

(def Message
  [:and
   [:map
    [:audio {:optional true} :string]
    [:id {:optional true} :string]
    [:text {:optional true} :string]
    [:transcript {:optional true} :string]
    [:type [:enum "input_text" "input_audio" "item_reference" "text"]]]
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (str "Invalid parameters for message of type: " (:type value)))}
    (fn [{:keys [audio id text transcript type]}]
      (condp = type
        "input_text"     (some? text)
        "text"           (some? text)
        "input_audio"    (and (some? audio) (some? transcript))
        "item_reference" (some? id)
        false))]])

(def ConversationItem
  [:and
   [:map
    [:arguments {:optional true} :string]
    [:call_id {:optional true} :string]
    [:content {:optional true} [:vector Message]]
    [:id {:optional true} :string]
    [:role Role]
    [:object {:optional true} [:enum "realtime.item"]]
    [:status {:optional true} [:enum "completed" "incomplete"]]
    [:type [:enum "message" "function_call" "function_call_output"]]]
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (condp = (:type value)
                       "message" "message only allows: content"
                       "function_call" "function_call only allows: arguments, name"
                       "function_call_output only allows: output"))}
    (fn [{:keys [type] :as ci}]
      (let [ks (set (keys ci))
            message       #{:content}
            function-call #{:arguments :name}
            function-call-output #{:output}]
        (cond
          (= "function_call" type)        (= 0 (count (set/intersection function-call-output message ks)))
          (= "function_call_output" type) (= 0 (count (set/intersection function-call message ks)))
          :else                           (= 0 (count (set/intersection function-call function-call-output ks))))))]])

(def Response
  [:map
   [:conversation {:optional true} [:enum "none" "auto"]]
   [:input {:optional true} [:vector ConversationItem]]
   [:instructions {:optional true} :string]
   [:max_response_output_tokens {:optional true} MaxResponseOutputTokens]
   [:metadata {:optional true} map?]
   [:modalities {:optional true} Modalities]
   [:output_audio_format {:optional true} AudioFormat]
   [:temperature {:optional true} number?]
   [:tool_choice {:optional true} ToolChoice]
   [:tools {:optional true} [:vector Function]]
   [:voice {:optional true} Voice]])

(def TranscriptionSession
  [:map
   [:input_audio_format {:optional true} AudioFormat]
   [:input_audio_noise_reduction {:optional true} InputAudioNoiseReduction]
   [:input_audio_transcription {:optional true} InputAudioTranscription]
   [:modalities {:optional true} Modalities]
   [:turn_detection {:optional true} [:maybe TurnDetection]]])

;;; Client Events

(def SessionUpdate
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "session.update"]]
   [:session Session]])

(def ConversationItemCreate
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "conversation.item.create"]]
   [:item ConversationItem]
   [:previous_item_id {:optional true} :string]])

(def ConversationItemRetrieve
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "conversation.item.retrieve"]]
   [:item_id :string]])

(def ConversationItemTruncate
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "conversation.item.truncate"]]
   [:audio_end_ms :int]
   [:content_index [:enum 0]]
   [:item_id :string]])

(def ConversationItemDelete
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "conversation.item.delete"]]
   [:item_id :string]])

(def ResponseCreate
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "response.create"]]
   [:response Response]])

(def ResponseCancel
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "response.cancel"]]
   [:response_id :string]])

(def InputAudioBufferAppend
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "input_audio_buffer.append"]]
   [:audio :string]])

(def InputAudioBufferCommit
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "input_audio_buffer.commit"]]])

(def InputAudioBufferClear
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "input_audio_buffer.clear"]]])

(def TranscriptionSessionUpdate
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "transcription_session.update"]]
   [:session TranscriptionSession]])

;;; output_audio_buffer.clear is WebRTC only
(def OutputAudioBufferClear
  [:map
   [:event_id {:optional true} :string]
   [:type [:enum "output_audio_buffer.clear"]]])

(def ClientEvent
  [:multi {:dispatch :type}
   ["session.update" SessionUpdate]
   ["conversation.item.create" ConversationItemCreate]
   ["response.create" ResponseCreate]
   ["response.cancel" ResponseCancel]
   ["input_audio_buffer.append" InputAudioBufferAppend]
   ["input_audio_buffer.commit" InputAudioBufferCommit]
   ["input_audio_buffer.clear" InputAudioBufferClear]
   ["conversation.item.retrieve" ConversationItemRetrieve]
   ["conversation.item.truncate" ConversationItemTruncate]
   ["conversation.item.delete" ConversationItemDelete]
   ["transcription_session.update" TranscriptionSessionUpdate]
   ["output_audio_buffer.clear" OutputAudioBufferClear]])

(defn validate
  "Validate the given client event. Returns if valid, otherwise throws an ex-info
  containing the humanized explanation and the event that was given. Should probably
  be used in development only"
  [event]
  (if-some [explain (m/explain ClientEvent event)]
    (throw (ex-info "Invalid client event given" {:humanized (me/humanize explain)
                                                  :event     event}))
    event))
