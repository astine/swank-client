(ns swank-client.core
  (:import [java.net Socket]
	   [java.io PrintWriter OutputStreamWriter InputStreamReader BufferedReader]))

(def *server* {:name "localhost" :port 4005})

(declare connection-handler)

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

(defn make-form [command & args]
  (list :emacs-rex (cons command args) "user" 't 1))

(defn slime-rex [connection command & args]
  (send-sexp connection (list :emacs-rex (cons command args) "user" 't 1)))

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
    (eval-form conn '(def swank:*configure-emacs-indentation* false))
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
      (case (first message)
	    :return (println (second (second message)))
	    :write-string (println (second message))
	    :debug (print-debug-trace message)
	    :indentation-update nil
	    (println message)))))

;(defn make-form [form]
  ;(list :emacs-rex (list 'swank:interactive-eval (str form)) "user" 't 1))

(defn debugger-restart [connection level restart-number]
  (slime-rex connection 'swank:invoke-nth-restart-for-emacs level restart-number))

(defn quit-debugger
  "Quit to toplevel"
  [connection]
  (slime-rex connection 'swank:throw-to-toplevel))

;(:debug 4 1
	;(Unable to resolve symbol: setq in this context   [Thrown class java.lang.Exception] nil)
	;((QUIT Quit to the SLIME top level))
	;((0 clojure.lang.Compiler.resolveIn(Compiler.java:5677) (:restartable nil))
	 ;(1 clojure.lang.Compiler.resolve(Compiler.java:5621) (:restartable nil))
	 ;(2 clojure.lang.Compiler.analyzeSymbol(Compiler.java:5584) (:restartable nil))
	 ;(3 clojure.lang.Compiler.analyze(Compiler.java:5172) (:restartable nil))
	 ;(4 clojure.lang.Compiler.analyze(Compiler.java:5151) (:restartable nil))
	 ;(5 clojure.lang.Compiler$InvokeExpr.parse(Compiler.java:3036) (:restartable nil))
	 ;(6 clojure.lang.Compiler.analyzeSeq(Compiler.java:5371) (:restartable nil))
	 ;(7 clojure.lang.Compiler.analyze(Compiler.java:5190) (:restartable nil))
	 ;(8 clojure.lang.Compiler.analyze(Compiler.java:5151) (:restartable nil))
	 ;(9 clojure.lang.Compiler$BodyExpr$Parser.parse(Compiler.java:4670) (:restartable nil)))
	;(nil))
;(:debug-activate 4 1 nil)
