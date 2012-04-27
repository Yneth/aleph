;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.core
  (:use
    [potemkin]
    [lamina core api])
  (:require
    [aleph.http.options :as options]
    [aleph.netty.client :as client]
    [aleph.formats :as formats]
    [aleph.netty :as netty]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    [java.io
     RandomAccessFile
     InputStream
     File]
    [org.jboss.netty.handler.codec.http
     DefaultHttpChunk
     DefaultHttpResponse
     DefaultHttpRequest
     HttpVersion
     HttpResponseStatus
     HttpResponse
     HttpRequest
     HttpMessage
     HttpMethod
     HttpRequest
     HttpChunk
     HttpHeaders]
    [org.jboss.netty.channel
     Channel]
    [org.jboss.netty.buffer
     ChannelBuffers]
    [java.nio.channels
     FileChannel
     FileChannel$MapMode]
    [java.net
     URLConnection
     InetAddress
     InetSocketAddress]))

(def request-methods [:get :post :put :delete :trace :connect :head :options :patch])

(def netty-method->keyword
  (zipmap
    (map #(HttpMethod/valueOf (name %)) request-methods)
    request-methods))

(def keyword->netty-method
  (zipmap
    (vals netty-method->keyword)
    (keys netty-method->keyword)))

(defn request-method [^HttpRequest request]
  (netty-method->keyword (.getMethod request)))

(defn response-code [^HttpResponse response]
  (-> response .getStatus .getCode))

(defn http-headers [^HttpMessage msg]
  (let [k (keys (.getHeaders msg))]
    (zipmap
      (map str/lower-case k)
      (map #(.getHeader msg %) k))))

(defn http-body [^HttpMessage msg]
  (.getContent msg))

(defn http-content-type [^HttpMessage msg]
  (.getHeader msg "Content-Type"))

(defn http-character-encoding [^HttpMessage msg]
  (when-let [content-type (.getHeader msg "Content-Type")]
    (->> (str/split content-type #"[;=]")
      (map str/trim)
      (drop-while #(not= % "charset"))
      second)))

(defn http-content-length [^HttpMessage msg]
  (when-let [content-length (.getHeader msg "Content-Length")]
    (try
      (Integer/parseInt content-length)
      (catch Exception e
        (log/error e (str "Error parsing content-length: " content-length))
        nil))))

(defn request-uri [^HttpRequest request]
  (first (str/split (.getUri request) #"[?]")))

(defn request-query-string [^HttpRequest request]
  (second (str/split (.getUri request) #"[?]")))

;;;

(defn normalize-headers [headers]
  (zipmap
    (map 
      #(->> (str/split (name %) #"-")
         (map str/capitalize)
         (str/join "-"))
      (keys headers))
    (vals headers)))

(defn guess-body-format [m]
  (let [body (:body m)]
    (when body
      (cond
        (string? body)
        ["text/plain" "utf-8"]

        (instance? File body)
        [(or
           (URLConnection/guessContentTypeFromName (.getName ^File body))
           "application/octet-stream")]

        (formats/bytes? body)
        ["application/octet-stream"]))))

(defn normalize-ring-map [m]
  (let [[type encoding] (guess-body-format m)]
    (-> m
      (update-in [:headers] normalize-headers)
      (update-in [:headers "Connection"]
        #(or %
           (if (:keep-alive? m)
             "keep-alive"
             "close")))
      (update-in [:content-type] #(or % type))
      (update-in [:character-encoding] #(or % encoding))
      (update-in [:headers "Content-Type"]
        #(or %
           (when (:body m)
             (str
               (:content-type m)
               (when-let [charset (:character-encoding m)]
                 (str "; charset=" charset)))))))))

;;;

(defn decode-body [content-type character-encoding body]
  (when body
    (let [charset (or character-encoding (options/charset))]
      (cond
        (.startsWith ^String content-type "text/")
        (formats/bytes->string body charset)
        
        (= content-type "application/json")
        (formats/decode-json body)
        
        (= content-type "application/xml")
        (formats/decode-xml body)
        
        :else
        body))))

(defn decode-message [{:keys [content-type character-encoding body] :as msg}]
  (if (channel? body)
    (run-pipeline (reduce* conj [] body)
      #(decode-message (assoc msg :body %)))
    (assoc msg :body (decode-body content-type character-encoding body))))

;;;

(defn expand-writes [f honor-keep-alive? ch]
  (let [ch* (channel)
        default-charset (options/charset)]
    (bridge-join ch "aleph.http.core/expand-writes"
      (fn [m]
        (let [{:keys [msg chunks write-callback]} (f m)
              result (enqueue ch* msg)
              final-stage (fn [_]
                            (when write-callback
                              (write-callback))
                            (if (and honor-keep-alive? (not (:keep-alive? m)))
                              (close ch*)
                              true))] 
          (if-not chunks

            ;; non-streaming response
            (run-pipeline result
              final-stage)

            ;; streaming response
            (run-pipeline nil
              {:error-handler (fn [_])}
              (fn [_]
                (siphon
                  (map*
                    #(DefaultHttpChunk.
                       (formats/bytes->channel-buffer %
                         (or (:character-encoding m) default-charset)))
                    chunks)
                  ch*)
                (drained-result chunks))
              (fn [_]
                (enqueue ch* HttpChunk/LAST_CHUNK))
              final-stage))))
      ch*)
    ch*))

(defn collapse-reads [ch]
  (let [ch* (channel)
        current-stream (atom nil)]
    (bridge-join ch "aleph.http.core/collapse-reads"
      (fn [msg]
        (if (instance? HttpMessage msg)

          ;; headers
          (if-not (.isChunked ^HttpMessage msg)
            (enqueue ch* {:msg msg})
            (let [chunks (channel)]
              (reset! current-stream chunks)
              (enqueue ch* {:msg msg, :chunks chunks})))

          ;; chunk
          (if (.isLast ^HttpChunk msg)
            (close @current-stream)
            (enqueue @current-stream msg))))
      ch*)
    ch*))

;;;

(def-custom-map LazyMap
  :get
  (fn [_ data _ key default-value]
    `(if-not (contains? ~data ~key)
       ~default-value
       (let [val# (get ~data ~key)]
         (if (delay? val#)
           @val#
           val#)))))

(defn lazy-map [& {:as m}]
  (LazyMap. m))

(defn netty-request->ring-map [{netty-request :msg, chunks :chunks}]
  (let [netty-channel (netty/current-channel)
        request (lazy-map
                  :scheme :http
                  :keep-alive? (HttpHeaders/isKeepAlive netty-request)
                  :remote-addr (delay (netty/channel-remote-host-address netty-channel))
                  :server-name (delay (netty/channel-local-host-address netty-channel))
                  :server-port (delay (netty/channel-local-port netty-channel))
                  :request-method (delay (request-method netty-request))
                  :headers (delay (http-headers netty-request))
                  :content-type (delay (http-content-type netty-request))
                  :character-encoding (delay (http-character-encoding netty-request))
                  :uri (delay (request-uri netty-request))
                  :query-string (delay (request-query-string netty-request))
                  :content-length (delay (http-content-length netty-request)))]
    (if chunks
      (assoc request
        :body (map* #(.getContent ^HttpChunk %) chunks))
      (assoc request
        :body (let [content (.getContent ^HttpMessage netty-request)]
                (when (pos? (.readableBytes content))
                  content))))))

(defn netty-response->ring-map [{netty-response :msg, chunks :chunks}]
  (let [response (lazy-map
                   :keep-alive? (HttpHeaders/isKeepAlive netty-response)
                   :headers (delay (http-headers netty-response))
                   :character-encoding (delay (http-character-encoding netty-response))
                   :content-type (delay (http-content-type netty-response))
                   :content-length (delay (http-content-length netty-response))
                   :status (delay (response-code netty-response)))]
    (if chunks
      (assoc response
        :body (map* #(.getContent ^HttpChunk %) chunks))
      (assoc response
        :body (let [content (.getContent ^HttpMessage netty-response)]
                (when (pos? (.readableBytes content))
                  content))))))

(defn populate-netty-msg [m ^HttpMessage msg]
  (let [body (:body m)
        body (if (instance? InputStream body)
               (formats/input-stream->channel body)
               body)]

    ;; populate headers
    (doseq [[k v] (:headers m)]
      (when v
        (if (string? v)
          (.addHeader msg k v)
          (doseq [x v]
            (.addHeader msg k x)))))

    ;; populate body
    (cond

      (channel? body)
      (do
        (.setHeader msg "Transfer-Encoding" "chunked")
        {:msg msg
         :chunks body})

      (instance? File body)
      (let [fc (.getChannel (RandomAccessFile. body "r"))
            buf (-> fc
                  (.map FileChannel$MapMode/READ_ONLY 0 (.size fc))
                  ChannelBuffers/wrappedBuffer)]
        (.setContent msg buf)
        (HttpHeaders/setContentLength msg (.size fc))
        {:msg msg
         :write-callback #(.close fc)})

      :else
      (do
        (when body
          (let [encode #(formats/bytes->channel-buffer %
                          (or (:character-encoding m) (options/charset)))
                body (if (coll? body)
                       (encode (map encode body))
                       (encode body))]
            (.setContent msg body))
          (HttpHeaders/setContentLength msg (.readableBytes (.getContent msg))))
        {:msg msg}))))

(defn ring-map->netty-response [m]
  (let [m (normalize-ring-map m)
        m (update-in m [:body] #(if (nil? %) "" %))
        response (DefaultHttpResponse.
                   HttpVersion/HTTP_1_1
                   (HttpResponseStatus/valueOf (:status m)))]
    (populate-netty-msg m response)))

(defn ring-map->netty-request [m]
  (let [m (-> m
            normalize-ring-map)
        request (DefaultHttpRequest.
                  HttpVersion/HTTP_1_1
                  (-> m :request-method keyword->netty-method)
                  (str
                    (if (empty? (:uri m))
                      "/"
                      (:uri m))
                    (when-not (empty? (:query-string m))
                      (str "?" (:query-string m)))))]
    (.setHeader request "Host"
      (str (:server-name m)
        (when (:port m)
          (str ":" (:port m)))))
    (populate-netty-msg m request)))

;;;