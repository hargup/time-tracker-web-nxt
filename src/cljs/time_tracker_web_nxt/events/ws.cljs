(ns time-tracker-web-nxt.events.ws
  (:require-macros
   [cljs.core.async.macros :refer [go]])
  (:require
   [cljs.core.async :as async :refer [chan put! <! close! alts! take! timeout]]
   [re-frame.core :as rf]
   [taoensso.timbre :as timbre]
   [time-tracker-web-nxt.config :as config]
   [time-tracker-web-nxt.utils :as utils]
   [wscljs.client :as ws]
   [wscljs.format :as fmt]))

(defn ws-receive
  [{:keys [id started-time duration type] :as data}]
  (case type
    "create" (do
               (timbre/info "Create: " data)
               (rf/dispatch [:add-timer-to-db (dissoc data :type)])
               (timbre/debug "Starting timer: " id)
               (rf/dispatch [:start-timer (dissoc data :type)]))
    "update" (do
               (timbre/info "Update: " data)
               (if (and (nil? started-time) (> duration 0))
                 (do (timbre/debug "Stopping timer: " id)
                     (rf/dispatch [:stop-timer data]))))
    (timbre/debug "Unknown Action: " data)))

(defn ws-send [[data socket]]
  (ws/send socket (clj->js data) fmt/json))

(defn ws-create-and-start-timer
  [{:keys [db current-timestamp] :as cofx} [_ timer-project timer-note]]
  (let [[_ socket] (:conn db)
        timer-date (str (:timer-date db))]
    {:db (assoc db :show-create-timer-widget? false)
     :send [{:command "create-and-start-timer"
             :project-id (js/parseInt (:id timer-project) 10)
             :created-time (utils/datepicker-date->epoch timer-date current-timestamp)
             :notes timer-note} socket]}))

(defn ws-create [goog-auth-id]
  (let [response-chan (chan)
        handlers {:on-message #(do
                                 (ws-receive (fmt/read fmt/json (.-data %)))
                                 (put! response-chan (fmt/read fmt/json (.-data %))))}
        conn (ws/create (:conn-url config/env) handlers)]
    (go
      (let [data (<! response-chan)]
        (if (= "ready" (:type data))
          (do
            (ws/send conn (clj->js {:command "authenticate" :token goog-auth-id}) fmt/json)
            (if (= "success" (:auth-status (<! response-chan)))
              (rf/dispatch [:save-connection [response-chan conn]])
              (throw (ex-info "Authentication Failed" {}))))
          ;; TODO: Retry server connection
          (throw (ex-info "Server not ready" {})))))))

(defn ws-ping [[_ sock]]
  (go
    (while (= :open (ws/status sock))
      (<! (timeout 10000))
      (ws/send sock (clj->js {:command "ping"}) fmt/json))))

(defn ws-close [socket]
  (.log js/console "Closing websocket connection")
  (ws/close socket))

(defn init []
  (rf/reg-event-fx
   :create-ws-connection
   (fn [cofx [_ google-auth-token]]
     {:create google-auth-token}))

  (rf/reg-event-fx
   :save-connection
   (fn [{:keys [db] :as cofx} [_ sock]]
     {:db (assoc db :conn sock)
      :ping sock}))

  (rf/reg-fx :send ws-send)
  (rf/reg-fx :create ws-create)
  (rf/reg-fx :close ws-close)
  (rf/reg-fx :ping ws-ping))
