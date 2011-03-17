(ns swank-client.core
  (:import [java.net Socket]
	   [java.io PrintWriter OutputStreamWriter InputStreamReader BufferedReader]))

(declare connection-handler)
(declare dispatch-user-input)
(declare dispatch-event)

(def *server* {:name "localhost" :port 4005})
(def *connection* nil)

(def current-thread :repl-thread)
(def current-package "user")
(def current-continuation 0)

(def event-queue (ref nil))

(defmacro prog1 [& forms]
  `(let [return# ~(first forms)]
     ~@(rest forms)
     return#))

(defn code-message-length [length]
  (let [hex (Integer/toHexString length)]
    (str (apply str (repeat (- 6 (count hex)) "0")) hex)))

(defn send-sexp
  [connection sexp]
  (let [msg (str sexp "\n")
	full-message (str (code-message-length (count msg)) msg)]
    (doto (:out @connection)
      (.write full-message 0 (count full-message))
      (.flush))))

(defn next-continuation []
  (inc current-continuation))

(defn make-form [command & args]
  (list :emacs-rex (cons command args)
	current-package
	current-thread
	(next-continuation)))

(defn slime-rex [connection command & args]
  (send-sexp connection (apply make-form (cons command args))))

(defn eval-form [connection form]
  (slime-rex connection 'swank:interactive-eval (str form)))

(defn connect
  "Connect to server and return connection as a map
   of the input stream and output stream."
  [server]
  (let [socket (Socket. (:name server) (:port server))
	conn (ref {:in (BufferedReader. (InputStreamReader. (.getInputStream socket)))
		   :out (OutputStreamWriter. (.getOutputStream socket))})]
    (-> #(connection-handler conn) Thread. .start)
    conn))

(defn read-chars [number reader]
  (let [ret-array (char-array number)]
    (.read reader ret-array 0 number)
    (apply str ret-array)))

(defn read-sexp [connection]
  (let [in (:in @connection)
	msg-length (Integer/parseInt (read-chars 6 in) 16)]
    (binding [*read-eval* false]
      (read-string (read-chars msg-length in)))))

(defn print-debug-trace [debug-trace]
  (do (println (first (nth debug-trace 3)))
      (doseq [trace-level (nth debug-trace 5)]
	(println (second trace-level)))))
  
(defn connection-handler [connection]
  (while
      (nil? (:exit @connection))
    (let [message (read-sexp connection)]
      (dosync (ref-set event-queue (cons message @event-queue))))))

(defn debugger-restart [connection level restart-number]
  (slime-rex connection 'swank:invoke-nth-restart-for-emacs level restart-number))

(defn quit-debugger
  "Quit to toplevel"
  [connection]
  (slime-rex connection 'swank:throw-to-toplevel))

;; interactive loop

(defn prompt-read [prompt]
  (print (str prompt ": "))
  (flush)
  (read-line))

(defn dispatch-user-input []
  (let [input (prompt-read "user")]
    (case input
	  "quit" :break
	  "(quit)" :break
	  "" nil
	  (eval-form *connection* (read-string input)))))

(defn event-input-loop []
  (loop [last-loop-result nil]
    (when (not (= last-loop-result :break))
      (recur
       (do
	 (while (nil? @event-queue))
	 (println @event-queue)
	 (doseq [event (reverse (dosync (prog1 @event-queue
					       (ref-set event-queue nil))))]
	   (dispatch-event event))
	 (dispatch-user-input))))))

(defn dispatch-event [event]
  (case (first event)
	:return (println (second (second event)))
	:write-string (println (second event))
	:debug (do (print-debug-trace event)
		   (debugger-restart *connection* (nth event 2) 0)
		   nil)
	:debug-activate nil
	:debug-return nil
	:indentation-update nil
	(println event)))
	    
(defn main []
  (binding [*connection* (connect *server*)]
    (dispatch-user-input)
    (event-input-loop)))

