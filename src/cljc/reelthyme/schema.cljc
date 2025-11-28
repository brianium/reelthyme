(ns reelthyme.schema
  "A schema for client events sent to OpenAI"
  (:require [clojure.set :as set]))

(def FunctionToolChoice
  [:map {:closed true}
   [:type [:enum "function"]]
   [:name :string]])

(def FunctionTool
  [:map {:closed true}
   [:type [:enum "function"]]
   [:description :string]
   [:name :string]
   [:parameters {:description "A JSON schema as a Clojure map"} map?]])

(def MCPToolChoice
  [:map {:closed true}
   [:server_label :string]
   [:type [:enum "mcp"]]
   [:name :string]])

(def ToolNames
  [:and
   [:vector {:min 1} :string]
   [:fn (fn [tool-names]
          (apply distinct? tool-names))]])

(def MCPFilter
  [:map {:closed true}
   [:read_only :boolean]
   [:tool_names ToolNames]])

(def AllowedTools
  [:orn
   [:names ToolNames]
   [:filter MCPFilter]])

(def MCPRequireApproval
  [:orn
   [:string [:enum "always" "never"]]
   [:filters
    [:map {:closed true}
     [:always {:optional true} MCPFilter]
     [:never {:optional true} MCPFilter]]]])

(def MCPTool
  [:and
   [:map {:closed true}
    [:type [:enum "mcp"]]
    [:server_label :string]
    [:allowed_tools AllowedTools]
    [:authorization :string]
    [:connector_id {:optional true}
     [:enum "connector_dropbox" "connector_gmail" "connector_googlecalendar" "connector_googledrive" "connector_microsoftteams" "connector_outlookcalendar" "connector_outlookemail" "connector_sharepoint"]]
    [:headers {:optional true} [:map-of :string :string]]
    [:require_approval MCPRequireApproval]
    [:server_description {:optional true} :string]
    [:server_url :string]]
   [:fn (fn [tool]
          (or (contains? tool :connector_id)
              (contains? tool :server_url)))]])

(def Tool
  [:orn
   [:function FunctionTool]
   [:mcp MCPTool]])

(def Voice
  [:enum "alloy" "ash" "ballad" "coral" "echo" "sage" "shimmer" "verse" "marin" "cedar"])

(def AudioFormat
  [:orn
   [:pcm
    [:map {:closed true}
     [:rate [:enum 24000]]
     [:type [:enum "audio/pcm"]]]]
   [:pcmu
    [:map {:closed true}
     [:type [:enum "audio/pcmu"]]]]
   [:pcma
    [:map {:closed true}
     [:type [:enum "audio/pcma"]]]]])

(def InputAudioNoiseReduction
  [:map {:closed true}
   [:type [:enum "near_field" "far_field"]]])

(def AudioInputTranscription
  [:map {:closed true}
   [:language {:description "ISO-639-1 (e.g. en)" :optional true} :string]
   [:model {:optional true} [:enum "whisper-1" "gpt-4o-mini-transcribe" "gpt-4o-transcribe" "gpt-4o-transcribe-diarize"]]
   [:prompt {:optional true} :string]])

(def ServerVAD
  [:map {:closed true}
   [:type [:enum "server_vad"]]
   [:create_response {:optional true} :boolean]
   [:idle_timeout_ms {:optional true} :int]
   [:interrupt_response {:optional true} :boolean]
   [:prefix_padding_ms {:optional true} :int]
   [:silence_duration_ms {:optional true} :int]
   [:threshold {:optional true} number?]])

(def SemanticVAD
  [:map {:closed true}
   [:type [:enum "semantic_vad"]]
   [:create_response {:optional true} :boolean]
   [:eagerness {:optional true} [:enum "low" "medium" "high" "auto"]]
   [:interrupt_response {:optional true} :boolean]])

(def TurnDetection
  [:orn
   [:server-vad ServerVAD]
   [:semantic-vad SemanticVAD]])

(def AudioInput
  [:map {:closed true}
   [:format {:optional true} AudioFormat]
   [:noise_reduction {:optional true} [:maybe InputAudioNoiseReduction]]
   [:transcription {:optional true} [:maybe AudioInputTranscription]]
   [:turn_detection {:optional true} [:maybe TurnDetection]]])

(def AudioOutput
  [:map {:closed true}
   [:format {:optional true} AudioFormat]
   [:speed {:optional true :min 0.25 :max 1.5} :double]
   [:voice Voice]])

(def Audio
  [:map {:closed true}
   [:input {:optional true} AudioInput]
   [:output {:optional true} AudioOutput]])

(def Include
  [:and
   [:vector {:min 1} [:enum "item.input_audio_transcription.logprobs"]]
   [:fn (fn [include]
          (apply distinct? include))]])

(def MaxOutputTokens
  [:or :int [:enum "inf"]])

(def OutputModalities
  [:and
   [:vector {:min 1 :max 2} [:enum "audio" "text"]]
   [:fn {:error/message "Can be [\"audio\", \"text\"] or [\"text\"]"}
    (fn [items]
      (and
       (not= ["audio"] items)
       (= (count items) (count (set items)))))]])

(def Prompt
  [:map {:closed true}
   [:id :string]
   [:variables {:optional true} [:map-of :keyword :any]]
   [:version {:optional true} :string]])

(def ToolChoice
  [:or
   [:enum "auto" "none" "required"]
   FunctionToolChoice
   MCPToolChoice])

(def RealtimeSession
  [:map {:closed true}
   [:type [:enum "realtime"]]
   [:audio {:optional true} Audio]
   [:include {:optional true} Include]
   [:instructions {:optional true} :string]
   [:max_output_tokens {:optional true} MaxOutputTokens]
   [:model {:optional true} :string]
   [:output_modalities {:optional true} OutputModalities]
   [:prompt {:optional true} Prompt]
   [:tool_choice {:optional true} ToolChoice]
   [:tools {:optional true} [:vector Tool]]])

(def TranscriptionSession
  [:map {:closed true}
   [:type [:enum "transcription"]]
   [:audio {:optional true} AudioInput]
   [:include {:optional true} Include]])

(def Session
  [:orn
   [:realtime RealtimeSession]
   [:transcription TranscriptionSession]])

;; Client Events

(def SystemContent
  [:map {:closed true}
   [:text :string]
   [:type [:enum "input_text"]]])

(def UserContent
  [:and
   [:map {:closed true}
    [:audio {:optional true} :string]
    [:detail {:optional true} [:enum "auto" "high" "low"]]
    [:image_url {:optional true} :string]
    [:text {:optional true} :string]
    [:transcript {:optional true} :string]
    [:type [:enum "input_text" "input_audio" "input_image"]]]
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (str "Invalid parameters for message of type: " (:type value)))}
    (fn [{:keys [audio text transcript type image_url]}]
      (condp = type
        "input_text"     (some? text)
        "input_audio"    (and (some? audio) (some? transcript))
        "input_image"    (some? image_url)
        false))]])

(def AssistantContent
  [:and
   [:map {:closed true}
    [:audio {:optional true} :string]
    [:text {:optional true} :string]
    [:transcript {:optional true} :string]
    [:type [:enum "output_text" "output_audio"]]]
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (str "Invalid parameters for message of type: " (:type value)))}
    (fn [{:keys [audio text transcript type]}]
      (condp = type
        "output_text" (some? text)
        "output_audio" (and (some? transcript) (some? audio))
        false))]])

(def MessageItem
  [:and
   [:map
    [:id {:optional true} :string]
    [:object {:optional true} :string]
    [:status {:optional true} :string]
    [:type [:= "message"]]
    [:role [:enum "user" "assistant" "system"]]]
   [:multi {:dispatch :role}
    ["user" [:map [:content UserContent]]]
    ["assistant" [:map [:content AssistantContent]]]
    ["system" [:map [:content SystemContent]]]]
   [:fn {:error/message "MessageItem is closed"}
    (fn [item]
      (let [expected-ks #{:id :object :status :type :role :content}
            actual-ks (-> item keys set)]
        (= #{} (set/difference actual-ks expected-ks))))]])

(def FunctionCallItem
  [:map {:closed true}
   [:id {:optional true} :string]
   [:object {:optional true} :string]
   [:status {:optional true} :string]
   [:arguments :string]
   [:name :string]
   [:type [:= "function_call"]]
   [:call_id :string]])

(def FunctionCallOutputItem
  [:map {:closed true}
   [:id {:optional true} :string]
   [:object {:optional true} :string]
   [:status {:optional true} :string]
   [:call_id :string]
   [:output :string]
   [:type [:= "function_call_output"]]])

(def MCPApprovalRequestItem
  [:map {:closed true}
   [:arguments :string]
   [:id :string]
   [:name :string]
   [:server_label :string]
   [:type [:= "mcp_approval_request"]]])

(def MCPApprovalResponseItem
  [:map {:closed true}
   [:approval_request_id :string]
   [:approve :boolean]
   [:id :string]
   [:type [:= "mcp_approval_response"]]
   [:reason {:optional true} :string]])

(def MCPListToolsItem
  [:map {:closed true}
   [:server_label :string]
   [:type [:= "mcp_list_tools"]]
   [:id :string]
   [:tools
    [:vector
     [:map {:closed true}
      [:input_schema
       [:or [:map-of :keyword :string]
        [:map-of :string :string]]]
      [:name :string]
      [:annotations {:optional true}
       [:or [:map-of :keyword :string]
        [:map-of :string :string]]]
      [:description :string]]]]])

(def MCPToolCallItem
  [:map {:closed true}
   [:arguments :string]
   [:id :string]
   [:name :string]
   [:server_label :string]
   [:type [:= "mcp_call"]]
   [:approval_request_id :string]
   [:error {:optional true}
    [:map {:closed true}
     [:code {:optional true} :int]
     [:message :string]
     [:type :string]]]
   [:output :string]])

(def ConversationItem
  [:multi {:dispatch :type}
   ["message" MessageItem]
   ["function_call" FunctionCallItem]
   ["function_call_output" FunctionCallOutputItem]
   ["mcp_approval_request" MCPApprovalRequestItem]
   ["mcp_approval_response" MCPApprovalResponseItem]
   ["mcp_list_tools" MCPListToolsItem]
   ["mcp_call" MCPToolCallItem]])

(def Response
  [:map {:closed true}
   [:audio {:optional true}
    [:map {:closed true}
     [:output AudioOutput]]]
   [:conversation {:optional true} [:enum "none" "auto"]]
   [:input {:optional true} [:vector ConversationItem]]
   [:instructions {:optional true} :string]
   [:max_response_output_tokens {:optional true} MaxOutputTokens]
   [:metadata {:optional true} [:or [:map-of :string :string]
                                [:map-of :keyword :string]]]
   [:output_modalities {:optional true} [:or [:tuple [:= "audio"]] [:tuple [:= "text"]]]]
   [:prompt {:optional true} Prompt]
   [:tool_choice {:optional true} ToolChoice]
   [:tools {:optional true} [:vector Tool]]])

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
   ["output_audio_buffer.clear" OutputAudioBufferClear]])
