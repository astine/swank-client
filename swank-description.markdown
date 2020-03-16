Slime is a very popular Emacs based IDE for Lisp and Lisp-like languages. It runs a Lisp process in the background to allow a developer using emacs to write his code within a running Lisp image, allow for such features as code evaluation and compilation, online documentation, completion etc. Slime is divided into a front-end and a back-end, the former being a major-mode for Emacs and the later being a server, called 'Swank', embedded into the Lisp image which runs in the background. These two communicate with a simple but undocumented protocol. This document is an attempt to provide a partial documentation of this protocol according to my own experience in reading the Slime and Swank source code and snooping on running sessions. Seeing as there is an apparent demand for alternative front ends and back ends for Slime, (ie. for other languages or editors/IDEs) I hope that this will aid in enriching the Slime ecosystem.

The basic Slime packet consists of a 6 character hex-string followed by an s-expression and terminated with a newline. For example:

        000016(:return (:ok nil) 1)\n

The hex-string contains the length of the message (s-expression plus newline) to follow. The s-expresssion is read by either Slime or Swank in a standard lisp fashion. The first element (a keyword) of the s-expression is the type of message being sent and any following elements are arguments. Message types beginning with 'emacs' such as ':emacs-rex' and ':emacs-interrupt' originate with emacs while all other message type originate with Swank.

## Swank message types
Here is a (partial) listing of message types:

### :emacs-rex - `(:emacs-rex 'form 'package 'thread 'continuation)`
   Remote Procedure Call
   
- form:         An s-expression containing the RPC call.
- package:      The current active Slime package (typically the package of the buffer the RPC is being sent from)
- thread:       The Swank-side thread that the command will be executed in. (Defaults to the thread in which the REPL runs)
- continuation: RPC exchanges are numbered so that Slime can match responses to requests. 

#### example:

        (:emacs-rex (swank:buffer-first-change "../program/file.lisp") "cl-user" t 4)

Emacs-rex signals an RPC call from Slime. This is the primary means of communication from Slime to Swank. These commands are executed in the Swank package/namespace on the server side. Available RPC calls are documented later in this document.

---

### :return - `(:return 'return-expression 'continuation)`
   Return from RPC
   
- return-expression: An s-expression which contains the return status and value of the RPC call
- continuation:      RPC exchanges are numbered so that Slime can match responses to requests. 

#### example: 

        (:return (:ok 14) 5)

Return signals that an RPC call has completed, either successfully or unsuccessfully. The first value of 'return-expression' is return status. ':ok' signals that the RPC call was successful and a second value is the actual return value from the call. ':abort' signals that the call was interrupted.

---

### :write-string - `(:write-string 'value &optional :repl-result)`
   Text written to standard out from the Lisp image
   
- value:        A string which the Lisp image has written to standard output
- :repl-result: An optional value signaling that a value was returned by evaluated code

#### Example: 

        (:write-string "foo bar baz\n")

Write-string transports code which is to appear in the Slime REPL. Usually this is code that was written by the Lisp image to standard output, but sometimes this is code returned by an expression sent to Swank from the Slime REPL with the intent to be evaluated.

---

### :new-package - `(:new-package 'package-name 'prompt-string)`
   Signals that the current thread has changed packages/namespaces
   
- package-name:  The name of the new package
- prompt-string: A new string to be used for the REPL prompt (usually the same as package-name)

#### Example: 

        (:new-package "foo" "foo")

New-package signals that the current thread is changing packages. This is returned when Slime sends code from the REPL to be evaluated which results in a package or namespace change.

---

### :debug - `(:debug 'thread 'level 'condition 'restarts 'frames 'continuations)`
   Full description of an un-handled condition/exception
   
- thread:        The thread which threw the condition
- level:         The depth of the condition (IE. values greater than one indicate condition generated from within the debugger)
- condition:     An s-expression with a description of the condition thrown
- restarts:      A list of available restarts for this condition
- frames:        A stacktrace
- continuations: Pending continuations

#### Example: 

        (:debug 10 1
	          ("No message." "  [Thrown class java.lang.ClassCastException]" nil)
	          (("QUIT" "Quit to the SLIME top level"))
	          ((0 "java.lang.Class.cast(Class.java:2990)"
	              (:restartable nil))
	           (1 "clojure.core$cast.invoke(core.clj:293)"
	              (:restartable nil))
	           (2 "clojure.core$_PLUS_.invoke(core.clj:815)"
	              (:restartable nil))
	           (3 "foo$eval2891.invoke(NO_SOURCE_FILE:1)"
	              (:restartable nil))
	           (4 "clojure.lang.Compiler.eval(Compiler.java:5424)"
	              (:restartable nil))
	           (5 "clojure.lang.Compiler.eval(Compiler.java:5391)"
	              (:restartable nil))
	           (6 "clojure.core$eval.invoke(core.clj:2382)"
	              (:restartable nil))
	           (7 "swank.commands.basic$eval_region.invoke(basic.clj:47)"
	              (:restartable nil))
	           (8 "swank.commands.basic$eval_region.invoke(basic.clj:37)"
	              (:restartable nil))
	           (9 "swank.commands.basic$listener_eval.invoke(basic.clj:71)"
	              (:restartable nil)))
	          (nil))

Debug provides a full description of an unhandled condition/exception.

---

### :debug-activate - `(:debug-activate 'thread 'level)`
   Triggers Slime to begin a debugging session.
   
- thread:        The thread which threw the condition
- level:         The depth of the condition (IE. values greater than one indicate condition generated from within the debugger)

Slime should display the corresponging condition/exception to the user and prompt for a restart.

---

### :indentation-update - `(:indentation-update 'description)`
   A description of the current indentation depth/level
   
- description: An s-expression listing the forms which surround the point (cursor location) in Slime

#### Example: 

        (:indentation-update
           ((rec-seq . 1)
            (with-command-line . 3) (dothread-keeping-clj . 1) (dothread-keeping . 1)
            (dothread . 0) (binding-map . 1) (with-pretty-writer . 1)
            (with-pprint-dispatch . 1) (def-impl-write! . 0) (def-impl-enabled? . 0)
            (with-logs . 1) (def-impl-get-log . 0) (def-impl-name . 0)
            (with-connection . 1) (binding-map . 1) (with-pretty-writer . 1)
            (with-pprint-dispatch . 1) (with-system-properties . 1) (with-bindings . 0)
            (with-system-properties . 1) (with-emacs-package . 0) (dothread-swank . 0)
            (with-package-tracking . 0) (with-db-cond . 0) (doseq . 1)
            (letfn . 1) (cond . 0) (with-open . 1)
            (sync . 1) (let . 1) (dotimes . 1)
            (with-in-str . 1) (loop . 1) (with-out-str . 0)
            (when-not . 1) (with-loading-context . 0) (future . 0)
            (when-first . 1) (comment . 0) (condp . 2)
            (with-local-vars . 1) (with-bindings . 1) (when-let . 1)
            (while . 1) (case . 1) (locking . 1)
            (delay . 0) (io! . 0) (lazy-seq . 0)
            (when . 1) (binding . 1) (defslimefn . defun)
            (with-query-results . 2) (transaction . 0) (with-connection . 1)
            (catch-error . 0) (with-flags . 0) (with-base-url . 1)
            (ANY . 2) (POST . 2) (bind-request . 2)
            (DELETE . 2) (GET . 2) (HEAD . 2)
            (PUT . 2) (lex . 1) (docodepoints . 1)
            (dochars . 1) (with-in-reader . 1) (with-out-append-writer . 1)
            (with-out-writer . 1) (returning . 1) (continuously . 0)
            (failing-gracefully . 0))) 

List the forms surrounding the point and the level of indentation each one implies. Slime uses this information to properly auto-indent code while it is being typed.

---

## RPC Calls
Here is a (partial) list of available RPC calls

### swank:interactive-eval - `(swank:interactive-eval 'form)`
   Evaluate code Lisp image Slime is controlling

- form: Form to be evaluated in Lisp image

### swank:listener-eval - `(swank:listener-eval 'form)`
   Evaluate code Lisp image Slime is controlling

- form: Form to be evaluated in Lisp image

### swank:invoke-nth-restart-for-emacs - `(swank:invoke-nth-restart-for-emacs 'level 'restart-number)`
   Invoke a restart

- level:          The condition on which to invoke the restart
- restart-number: The number of the restart to invoke

### swank:throw-to-toplevel - `(swank:throw-to-toplevel)`
   Breaks out of the debugger
