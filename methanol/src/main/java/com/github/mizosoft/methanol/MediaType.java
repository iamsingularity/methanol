/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.text.CharMatcher.alphaNum;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.chars;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.closedRange;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.text.CharMatcher;
import java.nio.BufferUnderflowException;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types">MIME
 * type</a>. The {@linkplain #toString() text representation} of this class can be used as the value
 * of the {@code Content-Type} HTTP header.
 *
 * <p>Case insensitive attributes such as the type, subtype, parameter names or the value of the
 * charset parameter are converted into lower-case.
 */
// for parsedCharset which might be lazily initialized
@SuppressWarnings({"OptionalAssignedToNull", "OptionalUsedAsFieldOrParameterType"})
public class MediaType {

  // media-type     = type "/" subtype *( OWS ";" OWS parameter )
  // type           = token
  // subtype        = token
  // parameter      = token "=" ( token / quoted-string )

  // token          = 1*tchar
  // tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
  //                    / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
  //                    / DIGIT / ALPHA
  //                    ; any VCHAR, except delimiters
  static final CharMatcher TOKEN_MATCHER = chars("!#$%&'*+-.^_`|~")
      .or(alphaNum());

  // quoted-string  = DQUOTE *( qdtext / quoted-pair ) DQUOTE
  // qdtext         = HTAB / SP / %x21 / %x23-5B / %x5D-7E / obs-text
  // obs-text       = %x80-FF
  private static final CharMatcher QUOTED_TEXT_MATCHER = chars("\t \u0021")  // HTAB + SP + 0x21
      .or(closedRange(0x23, 0x5B))
      .or(closedRange(0x5D, 0x7E));

  // quoted-pair    = "\" ( HTAB / SP / VCHAR / obs-text )
  private static final CharMatcher QUOTED_PAIR_MATCHER = chars("\t ") // HTAB + SP
      .or(closedRange(0x21, 0x7E)); // VCHAR

  //  OWS = *( SP / HTAB )
  private static final CharMatcher OWS_MATCHER = chars("\t ");

  private static final String CHARSET_ATTRIBUTE = "charset";

  private final String type;
  private final String subtype;
  private final Map<String, String> parameters;
  private @MonotonicNonNull Optional<Charset> parsedCharset;

  private MediaType(String type, String subtype, Map<String, String> parameters) {
    this.type = type;
    this.subtype = subtype;
    this.parameters = parameters;
    parsedCharset = parameters.containsKey(CHARSET_ATTRIBUTE) ? null : Optional.empty();
  }

  /**
   * Returns the general type.
   */
  public String type() {
    return type;
  }

  /**
   * Returns the subtype.
   */
  public String subtype() {
    return subtype;
  }

  /**
   * Returns an immutable map representing the parameters.
   */
  public Map<String, String> parameters() {
    return parameters;
  }

  /**
   * Returns an {@code Optional} representing the value of the charset parameter. An empty {@code
   * Optional} is returned if no such parameter exists.
   *
   * @throws IllegalCharsetNameException if a charset parameter exists the value of which is
   *                                     invalid
   * @throws UnsupportedCharsetException if a charset parameter exists the value of which is not
   *                                     supported in this JVM
   */
  public Optional<Charset> charset() {
    Optional<Charset> charset = parsedCharset;
    if (charset == null) {
      String charsetName = parameters.get(CHARSET_ATTRIBUTE);
      charset = charsetName != null ? Optional.of(Charset.forName(charsetName)) : Optional.empty();
      parsedCharset = charset;
    }
    return charset;
  }

  /**
   * Returns either the value of the charset parameter or the given default charset if no such
   * parameter exists or if the charset has not support in this JVM.
   *
   * @param defaultCharset the charset to fallback to
   * @throws IllegalCharsetNameException if a charset parameter exists the value of which is invalid
   */
  public Charset charsetOrDefault(Charset defaultCharset) {
    requireNonNull(defaultCharset);
    try {
      return charset().orElse(defaultCharset);
    } catch (UnsupportedCharsetException ignored) {
      return defaultCharset;
    }
  }

  /**
   * Returns a new {@code MediaType} with this instance's type, subtype and parameters but with the
   * name of the given charset as the value of the charset parameter.
   *
   * @param charset the new type's charset
   */
  public MediaType withCharset(Charset charset) {
    requireNonNull(charset);
    MediaType mediaType = withParameter(CHARSET_ATTRIBUTE, charset.name());
    mediaType.parsedCharset = Optional.of(charset);
    return mediaType;
  }

  /**
   * Returns a new {@code MediaType} with this instance's type, subtype and parameters but with the
   * value of the parameter specified by the given name set to the given value.
   *
   * @param name  the parameter's name
   * @param value the parameter's value
   * @throws IllegalArgumentException if the given name or value is invalid
   */
  public MediaType withParameter(String name, String value) {
    return withParameters(Map.of(name, value));
  }

  /**
   * Returns a new {@code MediaType} with this instance's type, subtype and parameters but with each
   * of the given parameters' names set to their corresponding values.
   *
   * @param parameters the parameters to add or replace
   * @throws IllegalArgumentException if any of the given parameters is invalid
   */
  public MediaType withParameters(Map<String, String> parameters) {
    requireNonNull(parameters);
    return create(type, subtype, parameters, new LinkedHashMap<>(this.parameters));
  }

  /**
   * Tests the given object for equality with this instance. {@code true} is returned if the given
   * object is a {@code MediaType} and both instances's type, subtype and parameters are equal.
   *
   * @param obj the object to test for equality
   */
  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof MediaType)) {
      return false;
    }
    MediaType other = (MediaType) obj;
    return type.equals(other.type)
        && subtype.equals(other.subtype)
        && parameters.equals(other.parameters);
  }

  /**
   * Returns a hashcode for this media type.
   */
  @Override
  public int hashCode() {
    return Objects.hash(type, subtype, parameters);
  }

  /**
   * Returns a text representation of this media type that is compatible with the value of the
   * {@code Content-Type} header.
   */
  @Override
  public String toString() {
    String str = type + "/" + subtype;
    if (!parameters.isEmpty()) {
      String joinedParameters = parameters.entrySet()
          .stream()
          .map(e -> e.getKey() + "=" + escapeAndQuoteValue(e.getValue()))
          .collect(Collectors.joining("; "));
      str += "; " + joinedParameters;
    }
    return str;
  }

  /**
   * Returns a new {@code MediaType} with the given type and subtype.
   *
   * @param type    the general type
   * @param subtype the subtype
   * @throws IllegalArgumentException if the given type or subtype is invalid
   */
  public static MediaType of(String type, String subtype) {
    return of(type, subtype, Map.of());
  }

  /**
   * Returns a new {@code MediaType} with the given type, subtype and parameters.
   *
   * @param type       the general type
   * @param subtype    the subtype
   * @param parameters the parameters
   * @throws IllegalArgumentException if the given type, subtype or any of the given parameters is
   *                                  invalid
   */
  public static MediaType of(String type, String subtype, Map<String, String> parameters) {
    return create(type, subtype, parameters, new LinkedHashMap<>());
  }

  private static MediaType create(
      String type,
      String subtype,
      Map<String, String> parameters,
      LinkedHashMap<String, String> newParameters) {
    requireNonNull(type, "type");
    requireNonNull(subtype, "subtype");
    requireNonNull(parameters, "parameters");
    String normalizedType = normalizeToken(type);
    String normalizedSubtype = normalizeToken(subtype);
    for (var entry : parameters.entrySet()) {
      String normalizedAttribute = normalizeToken(entry.getKey());
      String normalizedValue;
      if (CHARSET_ATTRIBUTE.equals(normalizedAttribute)) {
        normalizedValue = normalizeToken(entry.getValue());
      } else {
        normalizedValue = entry.getValue();
        if (!QUOTED_PAIR_MATCHER.allMatch(normalizedValue)) {
          throw new IllegalArgumentException("Illegal value: '" + normalizedValue + "'");
        }
      }
      newParameters.put(normalizedAttribute, normalizedValue);
    }
    return new MediaType(
        normalizedType, normalizedSubtype, Collections.unmodifiableMap(newParameters));
  }

  /**
   * Parses the given string into a {@code MediaType} instance.
   *
   * @param value the media type string
   * @throws IllegalArgumentException if the given string is an invalid media type
   */
  public static MediaType parse(String value) {
    try {
      List<String> components = Component.parseComponents(value);
      Map<String, String> parameters = new LinkedHashMap<>();
      for (int i = 2; i < components.size(); i += 2) {
        parameters.put(components.get(i), components.get(i + 1));
      }
      return of(components.get(0), components.get(1), parameters);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException(format("Couldn't parse: '%s'", value), e);
    }
  }

  /**
   * From RFC 7230 section 3.2.6:
   *
   * <p>"A sender SHOULD NOT generate a quoted-pair in a quoted-string except where necessary to
   * quote DQUOTE and backslash octets occurring within that string."
   */
  private static String escapeAndQuoteValue(String value) {
    // If value is already a token then it doesn't need quoting
    // special case: if the value is empty then it is not a token
    if (TOKEN_MATCHER.allMatch(value) && !value.isEmpty()) {
      return value;
    }
    StringBuilder escaped = new StringBuilder();
    CharBuffer buffer = CharBuffer.wrap(value);
    escaped.append('"');
    while (buffer.hasRemaining()) {
      char c = buffer.get();
      if (c == '"' || c == '\\') {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    escaped.append('"');
    return escaped.toString();
  }

  private static String normalizeToken(String token) {
    if (!TOKEN_MATCHER.allMatch(token) || token.isEmpty()) {
      throw new IllegalArgumentException("Illegal token: '" + token + "'");
    }
    return toAsciiLowerCase(token);
  }

  private static String toAsciiLowerCase(CharSequence value) {
    StringBuilder lower = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        c += 32;
      }
      lower.append(c);
    }
    return lower.toString();
  }

  /**
   * A parse component in a media type string.
   */
  private enum Component {
    TYPE {
      @Override
      String read(CharBuffer buff) {
        return readToken(buff);
      }

      @Override
      Component next(CharBuffer buff) {
        requireLastChar(buff, '/');
        return SUBTYPE;
      }
    },

    SUBTYPE {
      @Override
      String read(CharBuffer buff) {
        return readToken(buff);
      }

      @Override
      @Nullable Component next(CharBuffer buff) {
        return consumeDelimiter(buff) ? NAME : null;
      }
    },

    NAME {
      @Override
      String read(CharBuffer buff) {
        return readToken(buff);
      }

      @Override
      Component next(CharBuffer buff) {
        requireLastChar(buff, '=');
        return VALUE;
      }
    },

    VALUE {
      @Override
      String read(CharBuffer buff) {
        if (consumeCharIfPresent(buff, '"')) { // quoted-string rule
          StringBuilder unescaped = new StringBuilder();
          while (!consumeCharIfPresent(buff, '"')) {
            char c = getCharacter(buff);
            if (!QUOTED_TEXT_MATCHER.matches(c)) {
              if (c != '\\') {
                throw new IllegalArgumentException(
                    format("Illegal char %#x in a quoted-string", (int) c));
              }
              c = getCharacter(buff);
              if (!QUOTED_PAIR_MATCHER.matches(c)) {
                throw new IllegalArgumentException(
                    format("Illegal char %#x in a quoted-pair", (int) c));
              }
            }
            unescaped.append(c);
          }
          return unescaped.toString();
        }
        return readToken(buff);
      }

      @Override
      @Nullable Component next(CharBuffer buff) {
        return consumeDelimiter(buff) ? NAME : null;
      }
    };

    abstract String read(CharBuffer buff);

    abstract @Nullable Component next(CharBuffer buff);

    char getCharacter(CharBuffer buff) {
      try {
        return buff.get();
      } catch (BufferUnderflowException e) {
        throw new IllegalStateException("Expected more: " + toString());
      }
    }

    void requireLastChar(CharBuffer buff, char c) {
      if (getCharacter(buff) != c) {
        throw new IllegalStateException("Expected a '" + c + "' after: " + toString());
      }
    }

    String readToken(CharBuffer buff) {
      int begin = buff.position();
      consumeIfPresent(buff, TOKEN_MATCHER);
      int end = buff.position();
      if (begin >= end) {
        // Tokens cannot be empty
        throw new IllegalStateException("Expected a token for: " + toString());
      }
      int originalPos = buff.position();
      CharBuffer subSequence = buff.position(begin).subSequence(0, end - begin);
      buff.position(originalPos);
      return subSequence.toString();
    }

    boolean consumeDelimiter(CharBuffer buff) {
      // 1*( OWS ";" OWS )
      if (buff.hasRemaining()) {
        consumeIfPresent(buff, OWS_MATCHER);
        requireLastChar(buff, ';'); // First delimiter must exist
        // Ignore dangling semicolons, see https://github.com/google/guava/issues/1726
        do {
          consumeIfPresent(buff, OWS_MATCHER);
        } while (consumeCharIfPresent(buff, ';'));
      }
      return buff.hasRemaining();
    }

    static void consumeIfPresent(CharBuffer buff, CharMatcher matcher) {
      while (buff.hasRemaining() && matcher.matches(buff.get(buff.position()))) {
        buff.get(); // consume
      }
    }

    static boolean consumeCharIfPresent(CharBuffer buff, char c) {
      if (buff.hasRemaining()) {
        if (buff.get(buff.position()) == c) {
          buff.get(); // consume
          return true;
        }
      }
      return false;
    }

    static List<String> parseComponents(String value) {
      CharBuffer valueBuff = CharBuffer.wrap(value);
      List<String> components = new ArrayList<>();
      for (Component c = TYPE; c != null; c = c.next(valueBuff)) {
        components.add(c.read(valueBuff));
      }
      return components;
    }
  }
}