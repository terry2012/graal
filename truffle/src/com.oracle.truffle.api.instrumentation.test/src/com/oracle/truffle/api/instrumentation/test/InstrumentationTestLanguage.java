/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.instrumentation.test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLanguage.Registration;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.instrumentation.Instrumentable;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.test.InstrumentationTest.ReturnLanguageEnv;
import com.oracle.truffle.api.instrumentation.test.InstrumentationTestLanguage.BlockNode;
import com.oracle.truffle.api.instrumentation.test.InstrumentationTestLanguage.DefineNode;
import com.oracle.truffle.api.instrumentation.test.InstrumentationTestLanguage.ExpressionNode;
import com.oracle.truffle.api.instrumentation.test.InstrumentationTestLanguage.FunctionsObject;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.KeyInfo;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * <p>
 * Minimal test language for instrumentation that enables to define a hierarchy of nodes with one or
 * multiple {@link SourceSection#getTags() source section tags}. If the DEFINE tag is used then the
 * first argument is an identifier and all following arguments are contents of a function. If CALL
 * is used then the first argument is used as identifier for a previously defined target. For the
 * tag LOOP the first argument is used for the number of iterations all body nodes should get
 * executed.
 * </p>
 *
 * <p>
 * Can eval expressions with the following syntax:
 * </p>
 * <code>
 * Statement ::= ident {":" ident} ["(" Statement {"," Statement} ")"
 * </code>
 *
 * <p>
 * Example for calling to a defined function 'foo' that loops 100 times over a statement with two
 * sub expressions:
 * </p>
 * <code>
 * ROOT(
 *     DEFINE(foo,
 *         LOOP(100, STATEMENT(EXPRESSION,EXPRESSION))
 *     ),
 *     STATEMENT:CALL(foo)
 * )
 * </code>
 * <p>
 * Other statements are:
 * <ul>
 * <li><code>ARGUMENT(name)</code> - copies a frame argument to the named slot</li>
 * <li><code>VARIABLE(name, value)</code> - defines a variable</li>
 * <li><code>CONSTANT(value)</code> - defines a constant value</li>
 * <li><code>PRINT(OUT, text)</code> or <code>PRINT(ERR, text)</code> - prints a text to standard
 * output resp. error output.</li>
 * <li><code>SPAWN(&lt;function&gt;)</code> - calls the function in a new thread</li>
 * <li><code>JOIN()</code> - waits for all spawned threads</li>
 * </ul>
 * </p>
 */
@Registration(id = InstrumentationTestLanguage.ID, mimeType = InstrumentationTestLanguage.MIME_TYPE, name = "InstrumentTestLang", version = "2.0")
@ProvidedTags({ExpressionNode.class, DefineNode.class, LoopNode.class,
                StandardTags.StatementTag.class, StandardTags.CallTag.class, StandardTags.RootTag.class, BlockNode.class, StandardTags.RootTag.class})
public class InstrumentationTestLanguage extends TruffleLanguage<Context>
                implements SpecialService {

    public static final String ID = "instrumentation-test-language";
    public static final String MIME_TYPE = "application/x-truffle-instrumentation-test-language";
    public static final String FILENAME_EXTENSION = ".titl";

    public static final Class<?> EXPRESSION = ExpressionNode.class;
    public static final Class<?> DEFINE = DefineNode.class;
    public static final Class<?> LOOP = LoopNode.class;
    public static final Class<?> STATEMENT = StandardTags.StatementTag.class;
    public static final Class<?> CALL = StandardTags.CallTag.class;
    public static final Class<?> ROOT = StandardTags.RootTag.class;
    public static final Class<?> BLOCK = BlockNode.class;

    public static final Class<?>[] TAGS = new Class<?>[]{EXPRESSION, DEFINE, LOOP, STATEMENT, CALL, BLOCK, ROOT};
    public static final String[] TAG_NAMES = new String[]{"EXPRESSION", "DEFINE", "CONTEXT", "LOOP", "STATEMENT", "CALL", "RECURSIVE_CALL", "BLOCK", "ROOT", "CONSTANT", "VARIABLE", "ARGUMENT",
                    "PRINT", "ALLOCATION", "SLEEP", "SPAWN", "JOIN", "INVALIDATE"};

    // used to test that no getSourceSection calls happen in certain situations
    private static int rootSourceSectionQueryCount;
    private final FunctionMetaObject functionMetaObject = new FunctionMetaObject();

    public InstrumentationTestLanguage() {
    }

    @Override
    public String fileExtension() {
        return FILENAME_EXTENSION;
    }

    /**
     * Set configuration data to the language. Possible values are:
     * <ul>
     * <li>{@link ReturnLanguageEnv#KEY} with a {@link ReturnLanguageEnv} value,</li>
     * <li>"initSource" with a {@link org.graalvm.polyglot.Source} value,</li>
     * <li>"runInitAfterExec" with a {@link Boolean} value.</li>
     * </ul>
     */
    public static Map<String, Object> envConfig;

    @Override
    protected Context createContext(TruffleLanguage.Env env) {
        Source initSource = null;
        Boolean runInitAfterExec = null;
        if (envConfig != null) {
            Object envReturner = envConfig.get(ReturnLanguageEnv.KEY);
            if (envReturner != null) {
                ((ReturnLanguageEnv) envReturner).env = env;
            }
            org.graalvm.polyglot.Source initPolyglotSource = (org.graalvm.polyglot.Source) envConfig.get("initSource");
            if (initPolyglotSource != null) {
                initSource = AbstractInstrumentationTest.sourceToImpl(initPolyglotSource);
            }
            runInitAfterExec = (Boolean) envConfig.get("runInitAfterExec");
        }
        return new Context(env, initSource, runInitAfterExec);
    }

    @Override
    protected void initializeContext(Context context) throws Exception {
        super.initializeContext(context);
        Source code = context.initSource;
        if (code != null) {
            SourceSection outer = code.createSection(0, code.getLength());
            BaseNode node = parse(code);
            RootCallTarget rct = Truffle.getRuntime().createCallTarget(new InstrumentationTestRootNode(this, "", outer, node));
            rct.call();
            if (context.runInitAfterExec) {
                context.afterTarget = rct;
            }
        }
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        Source code = request.getSource();
        SourceSection outer = code.createSection(0, code.getLength());
        BaseNode node;
        try {
            node = parse(code);
        } catch (LanguageError e) {
            throw new IOException(e);
        }
        RootCallTarget afterTarget = getContextReference().get().afterTarget;
        return Truffle.getRuntime().createCallTarget(new InstrumentationTestRootNode(this, "", outer, afterTarget, node));
    }

    public BaseNode parse(Source code) {
        return new Parser(this, code).parse();
    }

    private static final class Parser {

        private static final char EOF = (char) -1;

        private final InstrumentationTestLanguage lang;
        private final Source source;
        private final String code;
        private int current;

        Parser(InstrumentationTestLanguage lang, Source source) {
            this.lang = lang;
            this.source = source;
            this.code = source.getCharacters().toString();
        }

        public BaseNode parse() {
            BaseNode statement = statement();
            if (follows() != EOF) {
                error("eof expected");
            }
            return statement;
        }

        private BaseNode statement() {
            skipWhiteSpace();

            int startIndex = current;

            if (current() == EOF) {
                return null;
            }

            skipWhiteSpace();
            String tag = ident().trim().intern();
            if (!isValidTag(tag)) {
                throw new LanguageError(String.format("Illegal tag \"%s\".", tag));
            }

            int argumentIndex = 0;
            int numberOfIdents = 0;
            if (tag.equals("DEFINE") || tag.equals("ARGUMENT") || tag.equals("CALL") || tag.equals("LOOP") || tag.equals("CONSTANT") || tag.equals("SLEEP") || tag.equals("SPAWN")) {
                numberOfIdents = 1;
            } else if (tag.equals("VARIABLE") || tag.equals("RECURSIVE_CALL") || tag.equals("PRINT")) {
                numberOfIdents = 2;
            }
            String[] idents = new String[numberOfIdents];
            List<BaseNode> children = new ArrayList<>();

            if (follows() == '(') {

                skipWhiteSpace();

                if (current() == '(') {
                    next();
                    skipWhiteSpace();
                    int argIndex = 0;
                    while (current() != ')') {
                        if (argIndex < numberOfIdents) {
                            skipWhiteSpace();
                            idents[argIndex] = ident();
                        } else {
                            children.add(statement());
                        }
                        skipWhiteSpace();
                        if (current() != ',') {
                            break;
                        }
                        next();
                        argIndex++;
                    }
                    if (current() != ')') {
                        error("missing closing bracket");
                    }
                    next();
                }

            }
            for (String ident : idents) {
                if (ident == null) {
                    throw new LanguageError(numberOfIdents + " non-child parameters required for " + tag);
                }
            }
            SourceSection sourceSection = source.createSection(startIndex, current - startIndex);
            BaseNode[] childArray = children.toArray(new BaseNode[children.size()]);
            BaseNode node = createNode(tag, idents, sourceSection, childArray);
            if (tag.equals("ARGUMENT")) {
                ((ArgumentNode) node).setIndex(argumentIndex++);
            }
            node.setSourceSection(sourceSection);
            return node;
        }

        private static boolean isValidTag(String tag) {
            for (int i = 0; i < TAG_NAMES.length; i++) {
                String allowedTag = TAG_NAMES[i];
                if (tag == allowedTag) {
                    return true;
                }
            }
            return false;
        }

        private BaseNode createNode(String tag, String[] idents, SourceSection sourceSection, BaseNode[] childArray) throws AssertionError {
            switch (tag) {
                case "DEFINE":
                    return new DefineNode(lang, idents[0], sourceSection, childArray);
                case "CONTEXT":
                    return new ContextNode(childArray);
                case "ARGUMENT":
                    return new ArgumentNode(idents[0], childArray);
                case "CALL":
                    return new CallNode(idents[0], childArray);
                case "RECURSIVE_CALL":
                    return new RecursiveCallNode(idents[0], (Integer) parseIdent(idents[1]), childArray);
                case "LOOP":
                    return new LoopNode(parseIdent(idents[0]), childArray);
                case "BLOCK":
                    return new BlockNode(childArray);
                case "EXPRESSION":
                    return new ExpressionNode(childArray);
                case "ROOT":
                    return new FunctionRootNode(childArray);
                case "STATEMENT":
                    return new StatementNode(childArray);
                case "CONSTANT":
                    return new ConstantNode(idents[0], childArray);
                case "VARIABLE":
                    return new VariableNode(idents[0], idents[1], childArray, lang.getContextReference());
                case "PRINT":
                    return new PrintNode(idents[0], idents[1], childArray);
                case "ALLOCATION":
                    return new AllocationNode(new BaseNode[0]);
                case "SLEEP":
                    return new SleepNode(parseIdent(idents[0]), new BaseNode[0]);
                case "SPAWN":
                    return new SpawnNode(idents[0], childArray);
                case "JOIN":
                    return new JoinNode(childArray);
                case "INVALIDATE":
                    return new InvalidateNode(childArray);
                default:
                    throw new AssertionError();
            }
        }

        private void error(String message) {
            throw new LanguageError(String.format("error at %s. char %s: %s", current, current(), message));
        }

        private String ident() {
            StringBuilder builder = new StringBuilder();
            char c;
            while ((c = current()) != EOF && Character.isJavaIdentifierPart(c)) {
                builder.append(c);
                next();
            }
            if (builder.length() == 0) {
                error("expected ident");
            }
            return builder.toString();
        }

        private void skipWhiteSpace() {
            while (Character.isWhitespace(current())) {
                next();
            }
        }

        private char follows() {
            for (int i = current; i < code.length(); i++) {
                if (!Character.isWhitespace(code.charAt(i))) {
                    return code.charAt(i);
                }
            }
            return EOF;
        }

        private void next() {
            current++;
        }

        private char current() {
            if (current >= code.length()) {
                return EOF;
            }
            return code.charAt(current);
        }

    }

    private static class InstrumentationTestRootNode extends RootNode {

        private final String name;
        private final SourceSection sourceSection;
        private final RootCallTarget afterTarget;
        @Child private InstrumentedNode functionRoot;

        protected InstrumentationTestRootNode(InstrumentationTestLanguage lang, String name, SourceSection sourceSection, BaseNode... expressions) {
            this(lang, name, sourceSection, null, expressions);
        }

        protected InstrumentationTestRootNode(InstrumentationTestLanguage lang, String name, SourceSection sourceSection, RootCallTarget afterTarget, BaseNode... expressions) {
            super(lang);
            this.name = name;
            this.sourceSection = sourceSection;
            this.afterTarget = afterTarget;
            if (expressions.length == 1 && expressions[0] instanceof FunctionRootNode) {
                // It contains just a ROOT
                this.functionRoot = (FunctionRootNode) expressions[0];
            } else {
                this.functionRoot = new FunctionRootNode(expressions);
            }
            functionRoot.setSourceSection(sourceSection);
        }

        @Override
        public SourceSection getSourceSection() {
            rootSourceSectionQueryCount++;
            return sourceSection;
        }

        @Override
        protected boolean isInstrumentable() {
            return true;
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            Object returnValue = Null.INSTANCE;
            returnValue = functionRoot.execute(frame);
            if (afterTarget != null) {
                afterTarget.call();
            }
            return returnValue;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return "Root[" + name + "]";
        }
    }

    static final class ExpressionNode extends InstrumentedNode {

        ExpressionNode(BaseNode[] children) {
            super(children);
        }

    }

    @Instrumentable(factory = InstrumentedNodeWrapper.class)
    public abstract static class InstrumentedNode extends BaseNode {

        @Children private final BaseNode[] children;

        public InstrumentedNode(BaseNode[] children) {
            this.children = children;
        }

        public InstrumentedNode(@SuppressWarnings("unused") InstrumentedNode delegate) {
            this.children = null;
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            Object returnValue = Null.INSTANCE;
            for (BaseNode child : children) {
                if (child != null) {
                    returnValue = child.execute(frame);
                }
            }
            return returnValue;
        }

        @Override
        protected final boolean isTaggedWith(Class<?> tag) {
            if (tag == StandardTags.RootTag.class) {
                return this instanceof FunctionRootNode;
            } else if (tag == StandardTags.CallTag.class) {
                return this instanceof CallNode || this instanceof RecursiveCallNode;
            } else if (tag == StandardTags.StatementTag.class) {
                return this instanceof StatementNode;
            }
            return getClass() == tag;
        }

    }

    static final class BlockNode extends InstrumentedNode {

        BlockNode(BaseNode[] children) {
            super(children);
        }

    }

    private static final class FunctionRootNode extends InstrumentedNode {

        FunctionRootNode(BaseNode[] children) {
            super(children);
        }

    }

    private static final class StatementNode extends InstrumentedNode {

        StatementNode(BaseNode[] children) {
            super(children);
        }
    }

    static class DefineNode extends BaseNode {

        private final String identifier;
        private final CallTarget target;

        DefineNode(InstrumentationTestLanguage lang, String identifier, SourceSection source, BaseNode[] children) {
            this.identifier = identifier;
            String code = source.getCharacters().toString();
            int index = code.indexOf('(') + 1;
            index = code.indexOf(',', index) + 1;
            while (Character.isWhitespace(code.charAt(index))) {
                index++;
            }
            SourceSection functionSection = source.getSource().createSection(source.getCharIndex() + index, source.getCharLength() - index - 1);
            this.target = Truffle.getRuntime().createCallTarget(new InstrumentationTestRootNode(lang, identifier, functionSection, children));
        }

        @Override
        public Object execute(VirtualFrame frame) {
            defineFunction();
            return Null.INSTANCE;
        }

        @TruffleBoundary
        private void defineFunction() {
            Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
            if (context.callFunctions.callTargets.containsKey(identifier)) {
                if (context.callFunctions.callTargets.get(identifier) != target) {
                    throw new IllegalArgumentException("Identifier redefinition not supported.");
                }
            }
            context.callFunctions.callTargets.put(this.identifier, target);
        }

    }

    static class ContextNode extends BaseNode {

        @Children private final BaseNode[] children;

        ContextNode(BaseNode[] children) {
            this.children = children;
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            Object returnValue = Null.INSTANCE;
            TruffleContext inner = createInnerContext();
            Object prev = inner.enter();
            try {
                for (BaseNode child : children) {
                    if (child != null) {
                        returnValue = child.execute(frame);
                    }
                }
            } finally {
                inner.leave(prev);
                inner.close();
            }
            return returnValue;
        }

        @TruffleBoundary
        private TruffleContext createInnerContext() {
            Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
            return context.env.newContextBuilder().build();
        }
    }

    private static class CallNode extends InstrumentedNode {

        @Child private DirectCallNode callNode;
        private final String identifier;

        CallNode(String identifier, BaseNode[] children) {
            super(children);
            this.identifier = identifier;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (callNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
                CallTarget target = context.callFunctions.callTargets.get(identifier);
                callNode = insert(Truffle.getRuntime().createDirectCallNode(target));
            }
            return callNode.call(new Object[0]);
        }
    }

    private static class SpawnNode extends InstrumentedNode {

        @Child private DirectCallNode callNode;
        private final String identifier;

        SpawnNode(String identifier, BaseNode[] children) {
            super(children);
            this.identifier = identifier;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (callNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
                CallTarget target = context.callFunctions.callTargets.get(identifier);
                callNode = Truffle.getRuntime().createDirectCallNode(target);
            }
            spawnCall();
            return Null.INSTANCE;
        }

        @TruffleBoundary
        private void spawnCall() {
            Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
            Thread t = context.env.createThread(new Runnable() {
                @Override
                public void run() {
                    callNode.call(new Object[0]);
                }
            });
            t.start();
            context.spawnedThreads.add(t);
        }
    }

    private static class JoinNode extends InstrumentedNode {

        JoinNode(BaseNode[] children) {
            super(children);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            joinSpawnedThreads();
            return Null.INSTANCE;
        }

        @TruffleBoundary
        private void joinSpawnedThreads() {
            Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
            List<Thread> threads;
            do {
                threads = new ArrayList<>();
                synchronized (context.spawnedThreads) {
                    for (Thread t : context.spawnedThreads) {
                        if (t.isAlive()) {
                            threads.add(t);
                        }
                    }
                }
                for (Thread t : threads) {
                    try {
                        t.join();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            } while (!threads.isEmpty());
        }
    }

    private static class RecursiveCallNode extends InstrumentedNode {

        @Child private DirectCallNode callNode;
        private final String identifier;
        private final int depth;
        private int currentDepth = 0;

        RecursiveCallNode(String identifier, int depth, BaseNode[] children) {
            super(children);
            this.identifier = identifier;
            this.depth = depth;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (currentDepth < depth) {
                currentDepth++;
                if (callNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
                    CallTarget target = context.callFunctions.callTargets.get(identifier);
                    callNode = insert(Truffle.getRuntime().createDirectCallNode(target));
                }
                Object retval = callNode.call(new Object[0]);
                currentDepth--;
                return retval;
            } else {
                return null;
            }
        }
    }

    private static class AllocationNode extends InstrumentedNode {

        AllocationNode(BaseNode[] children) {
            super(children);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            getCurrentContext(InstrumentationTestLanguage.class).allocationReporter.onEnter(null, 0, 1);
            getCurrentContext(InstrumentationTestLanguage.class).allocationReporter.onReturnValue("Not Important", 0, 1);
            return null;
        }
    }

    private static class SleepNode extends InstrumentedNode {

        private final int timeToSleep;

        SleepNode(Object timeToSleep, BaseNode[] children) {
            super(children);
            this.timeToSleep = (Integer) timeToSleep;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            sleep();
            return null;
        }

        @TruffleBoundary
        private void sleep() {
            try {
                Thread.sleep(timeToSleep);
            } catch (InterruptedException e) {
            }
        }
    }

    private static class ConstantNode extends InstrumentedNode {

        private final Object constant;

        ConstantNode(String identifier, BaseNode[] children) {
            super(children);
            this.constant = parseIdent(identifier);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return constant;
        }

    }

    private static class InvalidateNode extends InstrumentedNode {

        InvalidateNode(BaseNode[] children) {
            super(children);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
            }
            return 1;
        }
    }

    private static Object parseIdent(String identifier) {
        if (identifier.equals("infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (identifier.equals("true")) {
            return true;
        } else if (identifier.equals("false")) {
            return false;
        }
        return Integer.parseInt(identifier);
    }

    private static final class VariableNode extends InstrumentedNode {

        private final String name;
        private final Object value;
        private final ContextReference<Context> contextRef;

        @CompilationFinal private FrameSlot slot;

        private VariableNode(String name, String value, BaseNode[] children, ContextReference<Context> contextRef) {
            super(children);
            this.name = name;
            this.value = parseIdent(value);
            this.contextRef = contextRef;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (contextRef.get().allocationReporter.isActive()) {
                // Pretend we're allocating the value, for tests
                contextRef.get().allocationReporter.onEnter(null, 0, getValueSize());
            }
            if (slot == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                slot = frame.getFrameDescriptor().findOrAddFrameSlot(name);
            }
            frame.setObject(slot, value);
            if (contextRef.get().allocationReporter.isActive()) {
                contextRef.get().allocationReporter.onReturnValue(value, 0, getValueSize());
            }
            super.execute(frame);
            return value;
        }

        private long getValueSize() {
            if (value instanceof Byte || value instanceof Boolean) {
                return 1;
            }
            if (value instanceof Character) {
                return 2;
            }
            if (value instanceof Short) {
                return 2;
            }
            if (value instanceof Integer || value instanceof Float) {
                return 4;
            }
            if (value instanceof Long || value instanceof Double) {
                return 8;
            }
            return AllocationReporter.SIZE_UNKNOWN;
        }

    }

    private static class ArgumentNode extends InstrumentedNode {

        private final String name;

        @CompilationFinal private FrameSlot slot;
        @CompilationFinal private int index;

        ArgumentNode(String name, BaseNode[] children) {
            super(children);
            this.name = name;
        }

        void setIndex(int index) {
            this.index = index;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (slot == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                slot = frame.getFrameDescriptor().findOrAddFrameSlot(name);
            }
            Object[] args = frame.getArguments();
            Object value;
            if (args.length <= index) {
                value = Null.INSTANCE;
            } else {
                value = args[index];
            }
            frame.setObject(slot, value);
            super.execute(frame);
            return value;
        }

    }

    static class LoopNode extends InstrumentedNode {

        private final int loopCount;
        private final boolean infinite;

        LoopNode(Object loopCount, BaseNode[] children) {
            super(children);
            boolean inf = false;
            if (loopCount instanceof Double) {
                if (((Double) loopCount).isInfinite()) {
                    inf = true;
                }
                this.loopCount = ((Double) loopCount).intValue();
            } else if (loopCount instanceof Integer) {
                this.loopCount = (int) loopCount;
            } else {
                throw new LanguageError("Invalid loop count " + loopCount);
            }
            this.infinite = inf;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object returnValue = Null.INSTANCE;
            for (int i = 0; infinite || i < loopCount; i++) {
                returnValue = super.execute(frame);
            }
            return returnValue;
        }
    }

    static class PrintNode extends InstrumentedNode {

        enum Output {
            OUT,
            ERR
        }

        private final Output where;
        private final String what;
        @CompilationFinal private PrintWriter writer;

        PrintNode(String where, String what, BaseNode[] children) {
            super(children);
            if (what == null) {
                this.where = Output.OUT;
                this.what = where;
            } else {
                this.where = Output.valueOf(where);
                this.what = what;
            }
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (writer == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                Context context = getRootNode().getLanguage(InstrumentationTestLanguage.class).getContextReference().get();
                switch (where) {
                    case OUT:
                        writer = new PrintWriter(new OutputStreamWriter(context.out));
                        break;
                    case ERR:
                        writer = new PrintWriter(new OutputStreamWriter(context.err));
                        break;
                    default:
                        throw new AssertionError(where);
                }
            }
            writeAndFlush(writer, what);
            return Null.INSTANCE;
        }

        @TruffleBoundary
        private static void writeAndFlush(PrintWriter writer, String what) {
            writer.write(what);
            writer.flush();
        }
    }

    public abstract static class BaseNode extends Node {

        private SourceSection sourceSection;

        public void setSourceSection(SourceSection sourceSection) {
            this.sourceSection = sourceSection;
        }

        @Override
        public SourceSection getSourceSection() {
            return sourceSection;
        }

        public abstract Object execute(VirtualFrame frame);

    }

    @SuppressWarnings("serial")
    private static class LanguageError extends RuntimeException {

        LanguageError(String format) {
            super(format);
        }
    }

    @Override
    protected Object findExportedSymbol(Context context, String globalName, boolean onlyExplicit) {
        return context.callFunctions.findFunction(globalName);
    }

    @Override
    protected Object getLanguageGlobal(Context context) {
        return context.callFunctions;
    }

    @Override
    protected boolean isObjectOfLanguage(Object object) {
        return object instanceof Function || object == functionMetaObject;
    }

    @Override
    protected Object findMetaObject(Context context, Object obj) {
        if (obj instanceof Integer || obj instanceof Long) {
            return "Integer";
        }
        if (obj instanceof Boolean) {
            return "Boolean";
        }
        if (obj != null && obj.equals(Double.POSITIVE_INFINITY)) {
            return "Infinity";
        }
        if (obj instanceof Function) {
            return functionMetaObject;
        }
        return null;
    }

    @Override
    protected SourceSection findSourceLocation(Context context, Object obj) {
        if (obj instanceof Integer || obj instanceof Long) {
            return Source.newBuilder("source integer").name("integer").mimeType(MIME_TYPE).build().createSection(1);
        }
        if (obj instanceof Boolean) {
            return Source.newBuilder("source boolean").name("boolean").mimeType(MIME_TYPE).build().createSection(1);
        }
        if (obj != null && obj.equals(Double.POSITIVE_INFINITY)) {
            return Source.newBuilder("source infinity").name("double").mimeType(MIME_TYPE).build().createSection(1);
        }
        return null;
    }

    @Override
    protected String toString(Context context, Object value) {
        if (value == functionMetaObject) {
            return "Function";
        } else {
            return Objects.toString(value);
        }
    }

    public static int getRootSourceSectionQueryCount() {
        return rootSourceSectionQueryCount;
    }

    static final class Function implements TruffleObject {

        private final CallTarget ct;

        Function(CallTarget ct) {
            this.ct = ct;
        }

        @Override
        public ForeignAccess getForeignAccess() {
            return FunctionMessageResolutionForeign.ACCESS;
        }

        public static boolean isInstance(TruffleObject obj) {
            return obj instanceof Function;
        }

        @MessageResolution(receiverType = Function.class)
        static final class FunctionMessageResolution {

            @Resolve(message = "EXECUTE")
            public abstract static class FunctionExecuteNode extends Node {

                public Object access(Function receiver, Object[] arguments) {
                    return receiver.ct.call(arguments);
                }
            }

            @Resolve(message = "IS_EXECUTABLE")
            public abstract static class FunctionIsExecutableNode extends Node {
                public Object access(Object receiver) {
                    return receiver instanceof Function;
                }
            }
        }
    }

    static final class Null implements TruffleObject {

        static final Null INSTANCE = new Null();

        private Null() {
        }

        public static boolean isInstance(TruffleObject obj) {
            return obj instanceof Null;
        }

        @Override
        public String toString() {
            return "Null";
        }

        @Override
        public ForeignAccess getForeignAccess() {
            return NullMessageResolutionForeign.ACCESS;
        }

        @MessageResolution(receiverType = Null.class)
        static final class NullMessageResolution {

            @Resolve(message = "IS_NULL")
            public abstract static class NullIsNullNode extends Node {

                public boolean access(Null aNull) {
                    return Null.INSTANCE == aNull;
                }
            }
        }
    }

    static class FunctionsObject implements TruffleObject {

        final Map<String, CallTarget> callTargets = new HashMap<>();
        final Map<String, TruffleObject> functions = new HashMap<>();

        FunctionsObject() {
        }

        TruffleObject findFunction(String name) {
            TruffleObject functionObject = functions.get(name);
            if (functionObject == null) {
                CallTarget ct = callTargets.get(name);
                if (ct == null) {
                    return null;
                }
                functionObject = new Function(ct);
                functions.put(name, functionObject);
            }
            return functionObject;
        }

        @Override
        public ForeignAccess getForeignAccess() {
            return FunctionsObjectMessageResolutionForeign.ACCESS;
        }

        public static boolean isInstance(TruffleObject obj) {
            return obj instanceof FunctionsObject;
        }

        @MessageResolution(receiverType = FunctionsObject.class)
        static final class FunctionsObjectMessageResolution {

            @Resolve(message = "KEYS")
            abstract static class FunctionsObjectKeysNode extends Node {

                @TruffleBoundary
                public Object access(FunctionsObject fo) {
                    return new FunctionNamesObject(fo.callTargets.keySet());
                }
            }

            @Resolve(message = "KEY_INFO")
            abstract static class FunctionsObjectKeyInfoNode extends Node {

                private static final int EXISTING_KEY = KeyInfo.newBuilder().setReadable(true).build();

                @TruffleBoundary
                public Object access(FunctionsObject fo, String name) {
                    if (fo.callTargets.containsKey(name)) {
                        return EXISTING_KEY;
                    } else {
                        return 0;
                    }
                }
            }

            @Resolve(message = "READ")
            abstract static class FunctionsObjectReadNode extends Node {

                @TruffleBoundary
                public Object access(FunctionsObject fo, String name) {
                    return fo.findFunction(name);
                }
            }
        }

        static final class FunctionNamesObject implements TruffleObject {

            private final Set<String> names;

            private FunctionNamesObject(Set<String> names) {
                this.names = names;
            }

            @Override
            public ForeignAccess getForeignAccess() {
                return FunctionNamesMessageResolutionForeign.ACCESS;
            }

            public static boolean isInstance(TruffleObject obj) {
                return obj instanceof FunctionNamesObject;
            }

            @MessageResolution(receiverType = FunctionNamesObject.class)
            static final class FunctionNamesMessageResolution {

                @Resolve(message = "HAS_SIZE")
                abstract static class FunctionNamesHasSizeNode extends Node {

                    @SuppressWarnings("unused")
                    public Object access(FunctionNamesObject namesObject) {
                        return true;
                    }
                }

                @Resolve(message = "GET_SIZE")
                abstract static class FunctionNamesGetSizeNode extends Node {

                    public Object access(FunctionNamesObject namesObject) {
                        return namesObject.names.size();
                    }
                }

                @Resolve(message = "READ")
                abstract static class FunctionNamesReadNode extends Node {

                    @CompilerDirectives.TruffleBoundary
                    public Object access(FunctionNamesObject namesObject, int index) {
                        if (index >= namesObject.names.size()) {
                            throw UnknownIdentifierException.raise(Integer.toString(index));
                        }
                        Iterator<String> iterator = namesObject.names.iterator();
                        int i = index;
                        while (i-- > 0) {
                            iterator.next();
                        }
                        return iterator.next();
                    }
                }

            }
        }

    }

    static class FunctionMetaObject implements TruffleObject {

        @Override
        public ForeignAccess getForeignAccess() {
            return FunctionMetaObjectMessageResolutionForeign.ACCESS;
        }

        public static boolean isInstance(TruffleObject obj) {
            return obj instanceof FunctionMetaObject;
        }

        @MessageResolution(receiverType = FunctionMetaObject.class)
        static final class FunctionMetaObjectMessageResolution {
        }
    }
}

class Context {

    final FunctionsObject callFunctions = new FunctionsObject();
    final Env env;
    final OutputStream out;
    final OutputStream err;
    final AllocationReporter allocationReporter;
    final Source initSource;
    final boolean runInitAfterExec;
    RootCallTarget afterTarget;
    final Set<Thread> spawnedThreads = new WeakSet<>();

    Context(Env env, Source initSource, Boolean runInitAfterExec) {
        this.env = env;
        this.out = env.out();
        this.err = env.err();
        this.allocationReporter = env.lookup(AllocationReporter.class);
        this.initSource = initSource;
        this.runInitAfterExec = runInitAfterExec != null && runInitAfterExec;
    }

    private static class WeakSet<T> extends AbstractSet<T> {

        private final Map<T, Void> map = new WeakHashMap<>();

        @Override
        public Iterator<T> iterator() {
            return map.keySet().iterator();
        }

        @Override
        public synchronized int size() {
            return map.size();
        }

        @Override
        public synchronized boolean add(T e) {
            return map.put(e, null) == null;
        }

        @Override
        public synchronized void clear() {
            map.clear();
        }

    }
}
