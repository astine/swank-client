(ns swank-client.core
  (:import [java.net Socket]
	   [java.io PrintWriter OutputStreamWriter InputStreamReader BufferedReader]))

(declare connection-handler)
(declare dispatch-user-input)
(declare dispatch-event)

(def ^{:doc "Address and port of swank server"} *server* {:name "localhost" :port 4005})
(def ^{:doc "Input and output stream connected to the server"} *connection* nil)

(def ^{:doc "Current server-side thread"} current-thread :repl-thread)
(def ^{:doc "Current server-side package"} current-package (atom "user"))
(def ^{:doc "Current RPC-call"} current-continuation (ref 0))
(def ^{:doc "RPC-calls which haven't been returned."} unreturned-continuations (ref nil))

(def ^{:doc "Queue of unhandled events"} event-queue (ref nil))

(defmacro prog1
  "Excecutes a sequence of forms, returning the value of the _first_ form executed."
  [& forms]
  `(let [return# ~(first forms)]
     ~@(rest forms)
     return#))

(defn next-continuation
  "Increments the continuation counter and adds it to the continuation stack.
   Returns the new continuation."
  []
  (dosync
   (alter current-continuation inc)
   (ref-set unreturned-continuations
	    (cons @current-continuation
		  @unreturned-continuations)))
  @current-continuation)

(defn clear-continuation
  "Clears a continuation from the continuation stack, indicating that swan
   has returned that evaluation."
  ([]
     (dosync (ref-set unreturned-continuations
		      (rest @unreturned-continuations))))
  ([continuation]
     (dosync (ref-set unreturned-continuations
		      (remove #(= % continuation) @unreturned-continuations)))))

(defn code-message-length
  "Create the hex-stream which contains the message length."
  [length]
  (let [hex (Integer/toHexString length)]
    (str (apply str (repeat (- 6 (count hex)) "0")) hex)))

(defn send-sexp
  "Sends a s-exp based message to swank."
  [connection sexp]
  (let [msg (str sexp "\n")
	full-message (str (code-message-length (count msg)) msg)]
    (doto (:out @connection)
      (.write full-message 0 (count full-message))
      (.flush))))


(defn make-form
  "Constructs an rpc s-exp for sending to swank."
  [command & args]
  (list :emacs-rex (cons command args)
	@current-package
	current-thread
	(next-continuation)))

(defn slime-rex
  "Send an rpc call to swank."
  [connection command & args]
  (send-sexp connection (apply make-form (cons command args))))

(defn eval-form
  "Sends a form to the server to be evaluated in the lisp environment."
  [connection form]
  (slime-rex connection 'swank:interactive-eval (str form)))

(defn eval-repl-form
  "Sends a form to the server to be evaluated in the lisp environment."
  [connection form]
  (slime-rex connection 'swank:listener-eval (str form)))

(defn connect
  "Connect to server and return connection as a map
   of the input stream and output stream."
  [server]
  (let [socket (Socket. (:name server) (:port server))
	conn (ref {:in (BufferedReader. (InputStreamReader. (.getInputStream socket)))
		   :out (OutputStreamWriter. (.getOutputStream socket))})]
    (-> #(connection-handler conn) Thread. .start)
    conn))

(defn read-chars
  "Reads a set number of characters from an input stream."
  [number reader]
  (let [ret-array (char-array number)]
    (.read reader ret-array 0 number)
    (apply str ret-array)))

(defn read-sexp
  "Reads a single s-exp message from the connection."
  [connection]
  (let [in (:in @connection)
					;each message is prefixed with a 6 char hex value
					;dictating the length of the message
	msg-length (Integer/parseInt (read-chars 6 in) 16)] 
    (binding [*read-eval* false]
      (read-string (read-chars msg-length in)))))

  
(defn connection-handler
  "Listens for events and passes them on to the event-queue for handling when found."
  [connection]
  (while
      (nil? (:exit @connection))
    (let [message (read-sexp connection)]
      (dosync (ref-set event-queue (cons message @event-queue))))))

(defn debugger-restart
  "Sends a restart to the debugger."
  [connection level restart-number]
  (slime-rex connection 'swank:invoke-nth-restart-for-emacs level restart-number))

(defn quit-debugger
  "Quit to toplevel"
  [connection]
  (slime-rex connection 'swank:throw-to-toplevel))

;; interactive loop

(defn prompt-read
  "Prompt user for input and return the input."
  [prompt]
  (print (str prompt ": "))
  (flush)
  (read-line))

(defn dispatch-user-input
  "Prompt for and handles user input."
  []
  (let [input (prompt-read @current-package)]
    (case input
	  "quit" :break
	  "(quit)" :break
	  "" nil
	  (eval-repl-form *connection* (read-string input)))))

(defn event-input-loop
  "Primary program loop; schedules the handling of events and user input."
  []
  (loop [last-loop-result nil]
    (when (not (= last-loop-result :break))
      (loop []
	(while (empty? @event-queue))
	(doseq [event (reverse (dosync (prog1 @event-queue
					      (ref-set event-queue nil))))]
	  (dispatch-event event))
	(if-not (empty? @unreturned-continuations) (recur)))
      (recur
       (dispatch-user-input)))))

(defn print-debug-trace
  "Prints a :debug event, providing a stacktrace."
  [debug-trace]
  (do (println (first (nth debug-trace 3)))
      (doseq [trace-level (nth debug-trace 5)]
	(println (second trace-level)))))

(defn dispatch-event
  "Handles an event; :return events are returned, :debug events are escaped from immediately."
  [event]
  (case (first event)
	:return (do (clear-continuation))
	:write-string (do (print (if (and (> (count event) 2)
					  (= (nth event 2) :repl-result))
				   "" "- ")
				 (second event))
			  (flush))
	:debug (do (print-debug-trace event)
		   (quit-debugger *connection*)
		   nil)
	:new-package (swap! current-package (fn [op] (second event)))
	:debug-activate nil
	:debug-return nil
	:indentation-update nil
	(println event)))
	    
(defn main []
  (binding [*connection* (connect *server*)]
    (dispatch-user-input)
    (event-input-loop)))

