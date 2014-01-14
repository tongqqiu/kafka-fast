(ns kafka-clj.consumer

  (:require
            [group-redis.core :refer [create-group-connector join get-members close reentrant-lock release persistent-set* persistent-get]]
            [clojure.tools.logging :refer [info error]]
            [clj-tcp.client :refer [client write! read! close-all close-client]]
            [kafka-clj.produce :refer [shutdown message]]
            [kafka-clj.fetch :refer [create-fetch-producer create-offset-producer send-offset-request send-fetch]]
            [kafka-clj.metadata :refer [get-metadata]]
            [fun-utils.core :refer [buffered-chan]]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :refer [<!! >!! alts!! timeout chan go >! <! close!]])
  (:import [kafka_clj.fetch_codec FetchMessage FetchError FetchEnd]))



 ;------- partition lock and release api

(defn- flatten-broker-partitions [broker-offsets]
  (for [[broker topics] broker-offsets
        [topic partitions] topics
        partition partitions]
    (assoc partition :broker broker :topic topic)))

(defn- get-add-partitions [broker-partitions n]
  "Returns n partitions that are not marked as locked"
  (take n (filter (complement :locked) broker-partitions)))

(defn- get-remove-partitions [broker-partitions n]
  "Returns n partitions that should be removed and that are locked"
  (take n (filter :locked broker-partitions)))

 (defn get-partitions-to-lock [topic broker-offsets members]
   "broker-offsets {broker {topic [{:partition :offset :topic}]}}
    Returns {:add [] :remove []} the partitions to add are in add, and to remove are in remove
    each entry has the format {:broker {:host o :port p} :topic t :partition p :offset o}"
   (let [broker-partitions (filter #(= (:topic %) topic) (flatten-broker-partitions broker-offsets))
         partition-count (count broker-partitions)
         locked-partition-count (count (filter :locked broker-partitions))
         e (long (/ partition-count (count members)))
         l (rem partition-count (count members))]
     
     {:add (if (< locked-partition-count (+ e l)) (get-add-partitions broker-partitions (+ e l)) [])
      :remove (if (> locked-partition-count (+ e l)) (get-remove-partitions broker-partitions (- locked-partition-count (+ e l))) [])})) 
 
 
 ;------- end of partition lock and release api

(defn get-rest-of-partitions [broker topic partition state]
  "state should be {broker {topic [{:partition :offset :topic}... ] }}
   This method will return all of the data for a broker topic that does not have :partition == partition"
  (filter #(not (= (:partition %) partition)) (-> state (get broker) (get topic))))

(defn get-partition [broker topic partition state]
  "state should be {broker {topic [{:partition :offset :topic}... ] }}
   This method will return all of the data for a broker topic that does not have :partition == partition"
  (first (filter #(= (:partition %) partition) (-> state (get broker) (get topic)))))


(defn merge-broker-offsets [state d]
  "D is a collection of messages one per topic partition, that were last consumed from a fetch request,
   state is the broker-offsets {broker {topic [{:partition :offset :topic}]}}
   The function will merge d with state so that state will contain the latest offsets consumed,
   and then returns the new state
   "
  ;(info "merge " state " with " d)
  (reduce (fn [state [broker messages]]
                    (reduce (fn [state {:keys [topic partition offset error-code locked]}]
			                         (merge-with merge
                                 state
			                           (if (or (not error-code) (= error-code 0))
                                   {broker
			                                 {topic
			                                      (conj (get-rest-of-partitions broker topic partition state)
			                                            {:offset (inc offset) :locked locked
                                                   :partition partition :error-code (if error-code error-code 0)  })
                                         }
                                      })))
                            state messages))
          state d))

(defn- get-latest-offset [k current-offsets resp]
  "Helper function for send-request-and-wait, k is searched in resp, if no entry current-offsets is searched, and if none is found 0 is returned"
  (if-let [o (get resp k)]
    (:offset o)
    (if-let [o (get current-offsets k)]
      (let [l (dec (:offset o))] ;we decrement the current offset, th reason is this is the pinged offset, the last 
                                 ;consumed offset is always (dec pinged-offset)
        (if (> l 0) l 0))
      (throw (RuntimeException. (str "Cannot find " k " in " current-offsets))))))


(defn- write-persister-data [group-conn state]
  "Converts state to [[k val] ... ] and sends to persisent-set*"
  (persistent-set* group-conn (vec state)))
  
(defn get-persister [group-conn conf]
  (let [{:keys [offset-commit-freq] :or {offset-commit-freq 5000}} conf
        ch (chan 100)]
    
    (go 
      (loop [t (timeout offset-commit-freq) state {}]
          (let [[v c] (alts! [ch t])]
            (if (= c ch)
              (if (nil? v)
                 (write-persister-data group-conn state) ;channel is closed
		            (if (= c ch)
		              (recur t (assoc state (clojure.string/join "/" [(:topic v) (:partition v)]) (:offset v)))))
               ;timeout
              (do
                (write-persister-data group-conn state)
                  (recur (timeout offset-commit-freq)
                         {}))))))
    
    {:ch ch :p-close #(close! ch) :p-send #(>!! ch %)}))
                         
(defn send-request-and-wait [producer group-conn topic-offsets msg-ch {:keys [fetch-timeout] :or {fetch-timeout 10000} :as conf}]
  "Returns the messages, if any error was or timeout was detected the function returns otherwise it waits for a FetchEnd message
   and returns. "

  (send-fetch producer (map (fn [[k v]] [k v]) topic-offsets))

  (let [{:keys [p-close p-send]} (get-persister group-conn conf)
        {:keys [read-ch error-ch]} (:client producer)
        current-offsets (into {} (for [[topic v] topic-offsets
                                        msg   v]
                                      [#{topic (:partition msg)} (assoc msg :topic topic) ]))]
    (loop [resp {}]
       (let [[v c] (alts!! [read-ch error-ch (timeout fetch-timeout)])]
		    (if v
		      (if (= c read-ch)
		        (cond (instance? FetchEnd v) (do (p-close) (close-client (:client producer)) (vals resp))
                  :else ;assume FetchMessage
                     (do
                       (let [k #{(:topic v) (:partition v)}
                             latest-offset (get-latest-offset k current-offsets resp)
                             new-msg? (or (> (:offset v) latest-offset) (= (:offset v) 0)) ]
                         (if new-msg?
	                          (do 
                               (>!! msg-ch v)
                               (p-send v))
                            (error "Duplicate message " k " latest-offset " latest-offset " message offset " (:offset v)))

                       (recur (if new-msg? (assoc resp k v) resp)))))
		        (do (p-close) (error "Error while requesting data from " producer " for topics " (keys topic-offsets)) (vals resp)))
		        (do 
                (p-close) 
                (close-client (:client producer));we close to force a reconnect
                (error "Timeout while requesting data from " producer " for topics " (keys topic-offsets)) (vals resp)))))))


(defn consume-broker [producer group-conn topic-offsets msg-ch conf]
  "Send a request to the broker and waits for a response, error or timeout
   Then threads the call to the route-requests, and returns the result
   The result returned can be either, the last messages consumed per topic and partition,
   or -1 for error and -2 for timeout
   "
   (send-request-and-wait producer group-conn topic-offsets msg-ch conf))


(defn transform-offsets [topic offsets-response {:keys [use-earliest] :or {use-earliest false}}]
   "Transforms [{:topic topic :partitions {:partition :error-code :offsets}}]
    to {topic [{:offset offset :partition partition}]}"
   (let [topic-data (first (filter #(= (:topic %) topic) offsets-response))
         partitions (:partitions topic-data)]
     {(:topic topic-data)
            (doall (for [{:keys [partition error-code offsets]} partitions]
                     {:offset (if use-earliest (last offsets) (first offsets))
                      :error-code error-code
                      :locked false
                      :partition partition}))}))

  
(defn get-offsets [offset-producer topic partitions]
  "returns [{:topic topic :partitions {:partition :error-code :offsets}}]"
  ;we should send format [[topic [{:partition 0} {:partition 1}...]] ... ]
   (send-offset-request offset-producer [[topic (map (fn [x] {:partition x}) partitions)]] )
   (let [{:keys [offset-timeout] :or {offset-timeout 10000}} (:conf offset-producer)
         {:keys [read-ch error-ch]} (:client offset-producer)

         [v c] (alts!! [read-ch error-ch (timeout offset-timeout)])
         ]
     (if v
       (if (= c read-ch)
         v
         (throw (RuntimeException. (str "Error reading offsets from " offset-producer " for topic " topic " error: " v))))
       (throw (RuntimeException. (str "Timeout while reading offsets from " offset-producer " for topic " topic))))))

(defn get-broker-offsets [metadata topic conf]
  "Builds the datastructure {broker {topic [{:offset o :partition p} ...] }}"
   (let [topic-data (get metadata topic)
         by-broker (group-by second (map-indexed vector topic-data))]
        (into {}
		        (for [[broker v] by-broker]
		          ;here we have data {{:host "localhost", :port 1} [[0 {:host "localhost", :port 1}] [1 {:host "localhost", :port 1}]], {:host "abc", :port 1} [[2 {:host "abc", :port 1}]]}
		          ;doing map first v gives the partitions for a broker
		          (let [offset-producer (create-offset-producer broker conf)
		                offsets-response (get-offsets offset-producer topic (map first v))]
		            (shutdown offset-producer)
		            [broker (transform-offsets topic offsets-response conf)])))))

(defn create-producers [broker-offsets conf]
  "Returns created producers"
    (for [broker (keys broker-offsets)]
          (create-fetch-producer broker conf)
          ))


(defn consume-brokers! [producers group-conn broker-offsets msg-ch conf]
  "
   Broker-offsets should be {broker {topic [{:offset o :partition p} ...] }}
   Consume brokers and returns a list of lists that contains the last messages consumed, or -1 -2 where errors are concerned
   the data structure returned is {broker -1|-2|[{:offset o topic: a} {:offset o topic a} ... ] ...}
  "
  (info "Consume brokers !!: "  broker-offsets)
  (try
    (doall (into {} (pmap #(vector (:broker %)  (consume-broker % group-conn (get broker-offsets (:broker %)) msg-ch conf)) producers)))
   (finally
     (info ">>>>>>>>>>>>>>>>>>>>> END CONSUME BROKERS!")))
  )

(defn broker-error? [v]
  false
  )

(defn update-broker-offsets [broker-offsets v]
  "
   broker-offsets must be {broker {topic [{:offset o :partition p} ...] }}
   v must be {broker -1|-2|[{:offset o topic: a} {:offset o topic a} ... ] ...}"
   (merge-broker-offsets broker-offsets v)
  )


(defn close-and-reconnect [bootstrap-brokers topic producers conf]
  (doseq [producer producers]
    (shutdown producer))

  (if-let [metadata (get-metadata bootstrap-brokers topic)]
    (let [broker-offsets (doall (get-broker-offsets metadata topic conf))
          producers (doall (create-producers broker-offsets conf))]
      [producers broker-offsets])
    (throw (RuntimeException. "No metadata from brokers " bootstrap-brokers))))

(defn- ^long coerce-long [v]
  "Will return a long value, if v is a long its returned as is, if its a number its cast to a long,
   otherwise its converted to a string and Long/parseLong is used"
  (if (instance? Long v) v
    (if (instance? Number v) 
      (long v)
      (Long/parseLong (str v)))))

(defn- get-saved-offset [group-conn topic partition]
  "Retreives the offset saved for the topic partition or nil"
  (coerce-long 
         (persistent-get group-conn (clojure.string/join "/" [topic partition]))))

(defn- change-partition-lock [group-conn broker-offsets broker topic partition locked?]
  "broker-offsets = {broker {topic [{:partition :offset :topic}]}}
   change the locked value of a partition
   returns the modified broker-offsets"
  (let [rest-records (get-rest-of-partitions broker topic partition broker-offsets)
           p-record (get-partition broker topic partition broker-offsets)
           saved-offset (get-saved-offset group-conn topic partition)]
      (info "change-partition-lock " broker-offsets " with p-record " p-record " locked? " locked? " saved-offset " saved-offset)
      (if p-record (merge-with merge  {broker {topic (conj rest-records (assoc p-record :locked locked?
                                                                                        :offset (if saved-offset saved-offset 
                                                                                                    (:offset p-record) ) ))}})
        broker-offsets)))

    
(defn calculate-locked-offsets [topic group-conn broker-offsets]
  "broker-offsets have format  {broker {topic [{:partition :offset :topic}]}}
   calculate which offsets should be consumed based on the locks and other members
   returns the broker-offsets marked as locked or not as locked."
  (let [{:keys [add remove]} (get-partitions-to-lock topic broker-offsets (get-members group-conn))
				broker-offsets1
									    (loop [broker-offsets1 broker-offsets partitions add]
												     (if-let [record (first partitions)]
											             (let [{:keys [broker partition]} record]
																			(recur 
																		      (change-partition-lock group-conn
                                                                 broker-offsets1 
									                                               broker topic partition 
									                                               (reentrant-lock group-conn (str topic "/" partition)))
																				                (rest partitions)))
									                               broker-offsets1))]
        (info "add " add)
        (info "broker-offsets1 " broker-offsets1)
									    
        (loop [broker-offsets2 broker-offsets1 partitions remove]
          (if-let [record (first partitions)]
            (let [{:keys [broker partition]} record]
	            (recur
	                 (do
	                   (release group-conn (str topic "/" partition)) 
	                   (change-partition-lock broker-offsets2 broker topic partition false)) 
	                 (rest partitions)))
	            broker-offsets2))))
    
  
(defn consume-producers! [bootstrap-brokers
                          group-conn
                          producers topic broker-offsets-p msg-ch {:keys [fetch-poll-ms] :or {fetch-poll-ms 10000} :as conf}]
  "Consume from the current offsets,
   if any error the producers are closed and a reconnect is done, and consumption is tried again
   otherwise the broker-offsets are updated and the next fetch is done"

  (loop [producers producers broker-offsets1 broker-offsets-p]
      ;v is [broker data]
		  (let [v (consume-brokers! producers group-conn (calculate-locked-offsets  topic group-conn broker-offsets1) msg-ch conf)]
		    (if (broker-error? v)
		      (do
		         (info "Error close and reconnect")
		         (let [[producers broker-offsets] (close-and-reconnect bootstrap-brokers producers topic conf)]
		            (recur producers broker-offsets)))
		      (do
             (if (nil?  (second v)) ; if we were reading data, no need to pause
               (<!! (timeout fetch-poll-ms)))
             
             (recur producers (update-broker-offsets broker-offsets1 v))))

		      )))

(defn consume [bootstrap-brokers group-conn msg-ch topic conf]
  "Entry point for topic consumption,
   The cluster metadata is requested from the bootstrap-brokers, the topic offsets are sorted per broker.
   For each broker a producer is created that will control the sending and reading from the broker,
   then consume-producers is called in the background that will reconnect if needed,
   the method returns with {:msg-ch and :shutdown (fn []) }, shutdown should be called to stop all consumption for this topic"
  (if-let [metadata (get-metadata bootstrap-brokers {})]
    (let[broker-offsets (doall (get-broker-offsets metadata topic conf))
         producers (doall (create-producers broker-offsets conf))
         t (future (try
                     (consume-producers! bootstrap-brokers group-conn producers topic broker-offsets msg-ch conf)
                     (catch Exception e (error e e))))]
      {:msg-ch msg-ch :shutdown (fn [] (future-cancel t))}
      )
     (throw (Exception. (str "No metadata from brokers " bootstrap-brokers)))))

(defn consumer [bootstrap-brokers topics conf]
 "Creates a consumer and starts consumption"
  (let [msg-ch (chan)
        redis-conf (get conf :redis-conf {:heart-beat-freq 10})
        group-conn (let [c (create-group-connector (get redis-conf :redis-host "localhost") redis-conf)]
                     (join c)
                     c)
        consumers (doall
                       (for [topic (into #{} topics)]
                         (consume bootstrap-brokers group-conn msg-ch topic conf)))
       
        shutdown (fn []
                   (close group-conn)
                   (doseq [c consumers]
                     ((:shutdown c))))]
    
    {:shutdown shutdown :message-ch msg-ch :group-conn group-conn}))


(defn shutdown-consumer [{:keys [shutdown]}]
  "Shutsdown a consumer"
  (shutdown))

 (defn read-msg
   ([{:keys [message-ch]}]
       (<!! message-ch))
   ([{:keys [message-ch]} timeout-ms]
   (first (alts!! [message-ch (timeout timeout-ms)]))))

 