package com.typesafe.config.impl;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValueType;

final class Parser {
    /**
     * Parses an input stream, which must be in UTF-8 encoding and should be
     * buffered. Does not close the stream; you have to arrange to do that
     * yourself.
     */
    static AbstractConfigValue parse(SyntaxFlavor flavor, ConfigOrigin origin,
            InputStream input, IncludeHandler includer) {
        try {
            return parse(flavor, origin, new InputStreamReader(input, "UTF-8"),
                    includer);
        } catch (UnsupportedEncodingException e) {
            throw new ConfigException.BugOrBroken(
                    "Java runtime does not support UTF-8");
        }
    }

    static AbstractConfigValue parse(SyntaxFlavor flavor, ConfigOrigin origin,
            Reader input, IncludeHandler includer) {
        Iterator<Token> tokens = Tokenizer.tokenize(origin, input);
        return parse(flavor, origin, tokens, includer);
    }

    static AbstractConfigValue parse(SyntaxFlavor flavor, ConfigOrigin origin,
            String input, IncludeHandler includer) {
        return parse(flavor, origin, new StringReader(input), includer);
    }

    private static SyntaxFlavor flavorFromExtension(String name,
            ConfigOrigin origin) {
        if (name.endsWith(".json"))
            return SyntaxFlavor.JSON;
        else if (name.endsWith(".conf"))
            return SyntaxFlavor.CONF;
        else
            throw new ConfigException.IO(origin, "Unknown filename extension");
    }

    static AbstractConfigValue parse(File f, IncludeHandler includer) {
        return parse(null, f, includer);
    }

    static AbstractConfigValue parse(SyntaxFlavor flavor, File f,
            IncludeHandler includer) {
        ConfigOrigin origin = new SimpleConfigOrigin(f.getPath());
        try {
            return parse(flavor, origin, f.toURI().toURL(), includer);
        } catch (MalformedURLException e) {
            throw new ConfigException.IO(origin,
                    "failed to create url from file path", e);
        }
    }

    static AbstractConfigValue parse(URL url, IncludeHandler includer) {
        return parse(null, url, includer);
    }

    static AbstractConfigValue parse(SyntaxFlavor flavor, URL url,
            IncludeHandler includer) {
        ConfigOrigin origin = new SimpleConfigOrigin(url.toExternalForm());
        return parse(flavor, origin, url, includer);
    }

    static AbstractConfigValue parse(SyntaxFlavor flavor, ConfigOrigin origin,
            URL url, IncludeHandler includer) {
        AbstractConfigValue result = null;
        try {
            InputStream stream = new BufferedInputStream(url.openStream());
            try {
                result = parse(
                        flavor != null ? flavor : flavorFromExtension(
                                url.getPath(), origin), origin, stream,
                        includer);
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            throw new ConfigException.IO(origin, "failed to read url", e);
        }
        return result;
    }

    static private final class ParseContext {
        private int lineNumber;
        private Stack<Token> buffer;
        private Iterator<Token> tokens;
        private IncludeHandler includer;
        private SyntaxFlavor flavor;
        private ConfigOrigin baseOrigin;

        ParseContext(SyntaxFlavor flavor, ConfigOrigin origin,
                Iterator<Token> tokens, IncludeHandler includer) {
            lineNumber = 0;
            buffer = new Stack<Token>();
            this.tokens = tokens;
            this.flavor = flavor;
            this.baseOrigin = origin;
            this.includer = includer;
        }

        private Token nextToken() {
            Token t = null;
            if (buffer.isEmpty()) {
                t = tokens.next();
            } else {
                t = buffer.pop();
            }

            if (flavor == SyntaxFlavor.JSON) {
                if (Tokens.isUnquotedText(t)) {
                    throw parseError("Token not allowed in valid JSON: '"
                        + Tokens.getUnquotedText(t) + "'");
                } else if (Tokens.isSubstitution(t)) {
                    throw parseError("Substitutions (${} syntax) not allowed in JSON");
                }
            }

            return t;
        }

        private void putBack(Token token) {
            buffer.push(token);
        }

        private Token nextTokenIgnoringNewline() {
            Token t = nextToken();
            while (Tokens.isNewline(t)) {
                lineNumber = Tokens.getLineNumber(t);
                t = nextToken();
            }
            return t;
        }

        static class Element {
            StringBuilder sb;
            // an element can be empty if it has a quoted empty string "" in it
            boolean canBeEmpty;

            Element(String initial, boolean canBeEmpty) {
                this.canBeEmpty = canBeEmpty;
                this.sb = new StringBuilder(initial);
            }
        }

        private void addPathText(List<Element> buf, boolean wasQuoted,
                String newText) {
            int i = wasQuoted ? -1 : newText.indexOf('.');
            Element current = buf.get(buf.size() - 1);
            if (i < 0) {
                // add to current path element
                current.sb.append(newText);
                // any empty quoted string means this element can
                // now be empty.
                if (wasQuoted && current.sb.length() == 0)
                    current.canBeEmpty = true;
            } else {
                // "buf" plus up to the period is an element
                current.sb.append(newText.substring(0, i));
                // then start a new element
                buf.add(new Element("", false));
                // recurse to consume remainder of newText
                addPathText(buf, false, newText.substring(i + 1));
            }
        }

        private Path parsePathExpression(List<Token> expression) {
            // each builder in "buf" is an element in the path.
            List<Element> buf = new ArrayList<Element>();
            buf.add(new Element("", false));

            for (Token t : expression) {
                if (Tokens.isValueWithType(t, ConfigValueType.STRING)) {
                    AbstractConfigValue v = Tokens.getValue(t);
                    // this is a quoted string; so any periods
                    // in here don't count as path separators
                    String s = v.transformToString();

                    addPathText(buf, true, s);
                } else {
                    // any periods outside of a quoted string count as
                    // separators
                    String text;
                    if (Tokens.isValue(t)) {
                        // appending a number here may add
                        // a period, but we _do_ count those as path
                        // separators, because we basically want
                        // "foo 3.0bar" to parse as a string even
                        // though there's a number in it. The fact that
                        // we tokenize non-string values is largely an
                        // implementation detail.
                        AbstractConfigValue v = Tokens.getValue(t);
                        text = v.transformToString();
                    } else if (Tokens.isUnquotedText(t)) {
                        text = Tokens.getUnquotedText(t);
                    } else {
                        throw new ConfigException.BadPath(lineOrigin(),
                                "Token not allowed in path expression: "
                                + t);
                    }

                    addPathText(buf, false, text);
                }
            }

            PathBuilder pb = new PathBuilder();
            for (Element e : buf) {
                if (e.sb.length() == 0 && !e.canBeEmpty) {
                    throw new ConfigException.BadPath(
                            lineOrigin(),
                            buf.toString(),
                            "path has a leading, trailing, or two adjacent period '.' (use \"\" empty string if you want an empty element)");
                } else {
                    pb.appendKey(e.sb.toString());
                }
            }

            return pb.result();
        }

        // merge a bunch of adjacent values into one
        // value; change unquoted text into a string
        // value.
        private void consolidateValueTokens() {
            // this trick is not done in JSON
            if (flavor == SyntaxFlavor.JSON)
                return;

            List<Token> values = null; // create only if we have value tokens
            Token t = nextTokenIgnoringNewline(); // ignore a newline up front
            while (Tokens.isValue(t) || Tokens.isUnquotedText(t)
                    || Tokens.isSubstitution(t)) {
                if (values == null)
                    values = new ArrayList<Token>();
                values.add(t);
                t = nextToken(); // but don't consolidate across a newline
            }
            // the last one wasn't a value token
            putBack(t);

            if (values == null)
                return;

            if (values.size() == 1 && Tokens.isValue(values.get(0))) {
                // a single value token requires no consolidation
                putBack(values.get(0));
                return;
            }

            // this will be a list of String and Path
            List<Object> minimized = new ArrayList<Object>();

            // we have multiple value tokens or one unquoted text token;
            // collapse into a string token.
            StringBuilder sb = new StringBuilder();
            ConfigOrigin firstOrigin = null;
            for (Token valueToken : values) {
                if (Tokens.isValue(valueToken)) {
                    AbstractConfigValue v = Tokens.getValue(valueToken);
                    sb.append(v.transformToString());
                    if (firstOrigin == null)
                        firstOrigin = v.origin();
                } else if (Tokens.isUnquotedText(valueToken)) {
                    String text = Tokens.getUnquotedText(valueToken);
                    if (firstOrigin == null)
                        firstOrigin = Tokens.getUnquotedTextOrigin(valueToken);
                    sb.append(text);
                } else if (Tokens.isSubstitution(valueToken)) {
                    if (firstOrigin == null)
                        firstOrigin = Tokens.getSubstitutionOrigin(valueToken);

                    if (sb.length() > 0) {
                        // save string so far
                        minimized.add(sb.toString());
                        sb.setLength(0);
                    }
                    // now save substitution
                    List<Token> expression = Tokens
                            .getSubstitutionPathExpression(valueToken);
                    Path path = parsePathExpression(expression);
                    minimized.add(path);
                } else {
                    throw new ConfigException.BugOrBroken(
                            "should not be trying to consolidate token: "
                                    + valueToken);
                }
            }

            if (sb.length() > 0) {
                // save string so far
                minimized.add(sb.toString());
            }

            if (minimized.isEmpty())
                throw new ConfigException.BugOrBroken(
                        "trying to consolidate values to nothing");

            Token consolidated = null;

            if (minimized.size() == 1 && minimized.get(0) instanceof String) {
                consolidated = Tokens.newString(firstOrigin,
                        (String) minimized.get(0));
            } else {
                // there's some substitution to do later (post-parse step)
                consolidated = Tokens.newValue(new ConfigSubstitution(
                        firstOrigin, minimized));
            }

            putBack(consolidated);
        }

        private ConfigOrigin lineOrigin() {
            return new SimpleConfigOrigin(baseOrigin.description() + ": line "
                    + lineNumber);
        }

        private ConfigException parseError(String message) {
            return parseError(message, null);
        }

        private ConfigException parseError(String message, Throwable cause) {
            return new ConfigException.Parse(lineOrigin(), message, cause);
        }

        private AbstractConfigValue parseValue(Token token) {
            if (Tokens.isValue(token)) {
                return Tokens.getValue(token);
            } else if (token == Tokens.OPEN_CURLY) {
                return parseObject();
            } else if (token == Tokens.OPEN_SQUARE) {
                return parseArray();
            } else {
                throw parseError("Expecting a value but got wrong token: "
                        + token);
            }
        }

        private AbstractConfigObject parseObject() {
            // invoked just after the OPEN_CURLY
            Map<String, AbstractConfigValue> values = new HashMap<String, AbstractConfigValue>();
            ConfigOrigin objectOrigin = lineOrigin();
            boolean afterComma = false;
            while (true) {
                Token t = nextTokenIgnoringNewline();
                if (Tokens.isValueWithType(t, ConfigValueType.STRING)) {
                    String key = (String) Tokens.getValue(t).unwrapped();
                    Token afterKey = nextTokenIgnoringNewline();
                    if (afterKey != Tokens.COLON) {
                        throw parseError("Key not followed by a colon, followed by token "
                                + afterKey);
                    }

                    consolidateValueTokens();
                    Token valueToken = nextTokenIgnoringNewline();

                    // note how we handle duplicate keys: the last one just
                    // wins.
                    // FIXME in strict JSON, dups should be an error; while in
                    // our custom config language, they should be merged if the
                    // value is an object.
                    values.put(key, parseValue(valueToken));

                    afterComma = false;
                } else if (t == Tokens.CLOSE_CURLY) {
                    if (afterComma) {
                        throw parseError("expecting a field name after comma, got a close brace }");
                    }
                    break;
                } else {
                    throw parseError("Expecting close brace } or a field name, got "
                            + t);
                }
                t = nextTokenIgnoringNewline();
                if (t == Tokens.CLOSE_CURLY) {
                    break;
                } else if (t == Tokens.COMMA) {
                    // continue looping
                    afterComma = true;
                } else {
                    throw parseError("Expecting close brace } or a comma, got "
                            + t);
                }
            }
            return new SimpleConfigObject(objectOrigin, null, values);
        }

        private ConfigList parseArray() {
            // invoked just after the OPEN_SQUARE
            ConfigOrigin arrayOrigin = lineOrigin();
            List<AbstractConfigValue> values = new ArrayList<AbstractConfigValue>();

            consolidateValueTokens();

            Token t = nextTokenIgnoringNewline();

            // special-case the first element
            if (t == Tokens.CLOSE_SQUARE) {
                return new ConfigList(arrayOrigin,
                        Collections.<AbstractConfigValue> emptyList());
            } else if (Tokens.isValue(t)) {
                values.add(parseValue(t));
            } else if (t == Tokens.OPEN_CURLY) {
                values.add(parseObject());
            } else if (t == Tokens.OPEN_SQUARE) {
                values.add(parseArray());
            } else {
                throw parseError("List should have ] or a first element after the open [, instead had token: "
                        + t);
            }

            // now remaining elements
            while (true) {
                // just after a value
                t = nextTokenIgnoringNewline();
                if (t == Tokens.CLOSE_SQUARE) {
                    return new ConfigList(arrayOrigin, values);
                } else if (t == Tokens.COMMA) {
                    // OK
                } else {
                    throw parseError("List should have ended with ] or had a comma, instead had token: "
                            + t);
                }

                // now just after a comma
                consolidateValueTokens();

                t = nextTokenIgnoringNewline();
                if (Tokens.isValue(t)) {
                    values.add(parseValue(t));
                } else if (t == Tokens.OPEN_CURLY) {
                    values.add(parseObject());
                } else if (t == Tokens.OPEN_SQUARE) {
                    values.add(parseArray());
                } else {
                    throw parseError("List should have had new element after a comma, instead had token: "
                            + t);
                }
            }
        }

        AbstractConfigValue parse() {
            Token t = nextTokenIgnoringNewline();
            if (t == Tokens.START) {
                // OK
            } else {
                throw new ConfigException.BugOrBroken(
                        "token stream did not begin with START, had " + t);
            }

            t = nextTokenIgnoringNewline();
            AbstractConfigValue result = null;
            if (t == Tokens.OPEN_CURLY) {
                result = parseObject();
            } else if (t == Tokens.OPEN_SQUARE) {
                result = parseArray();
            } else if (t == Tokens.END) {
                throw parseError("Empty document");
            } else {
                throw parseError("Document must have an object or array at root, unexpected token: "
                        + t);
            }

            t = nextTokenIgnoringNewline();
            if (t == Tokens.END) {
                return result;
            } else {
                throw parseError("Document has trailing tokens after first object or array: "
                        + t);
            }
        }
    }

    private static AbstractConfigValue parse(SyntaxFlavor flavor,
            ConfigOrigin origin, Iterator<Token> tokens, IncludeHandler includer) {
        ParseContext context = new ParseContext(flavor, origin, tokens,
                includer);
        return context.parse();
    }
}