package com.ankoki.sakura;

import org.apache.commons.text.StringEscapeUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JSON extends LinkedHashMap<String, Object> implements Map<String, Object> {

	private static final Pattern KEY_PATTERN = Pattern.compile("\"(.+)?\":[ ]?");
	private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("\"(.+)?\":[ ]?(.+)");

	public static class MalformedJsonException extends RuntimeException {

		public MalformedJsonException() {
			super("There was an issue parsing your JSON.");
		}

		public MalformedJsonException(String message) {
			super(message);
		}

	}

	// <editor-fold defaultstate="collapsed" desc="External Static Methods">
	/**
	 * Converts a given Map to a JSON String.
	 *
	 * @param map         the map to convert.
	 * @param pretty      if the text should be 'pretty'.
	 * @param indentation indentation.
	 * @return the converted text.
	 */
	public static String toString(Map map, boolean pretty, int indentation) {
		return new StringifyJSON(map, pretty, indentation).toString();
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Internal Static Methods">
	/**
	 * <strong>INTERNAL USE ONLY</strong>
	 * <p>
	 * Matches a key value line into a pair.
	 *
	 * @param line the line to match.
	 * @return the key value pair.
	 * @throws MalformedJsonException if the line does not match the regex.
	 */
	private static Pair<String, Object> matchLine(String line) throws MalformedJsonException {
		if (line.endsWith("}")) line = StringUtils.replaceLast(line, "}", "");
		Matcher matcher = KEY_VALUE_PATTERN.matcher(line);
		if (matcher.matches()) {
			String key = matcher.group(1);
			String value = matcher.group(2);
			return new Pair<>(key, JSON.parseValue(value));
		}
		throw new MalformedJsonException("There was an issue parsing a line. Malformed Line: '" + line + "'");
	}

	/**
	 * <strong>INTERNAL USE ONLY</strong>
	 * <p>
	 * Parses a given value.
	 *
	 * @param value into its linked type.
	 * @return the correct object.
	 * @throws MalformedJsonException if there's an error in the value.
	 */
	private static Object parseValue(String value) throws MalformedJsonException {
		if (value.endsWith("}")) value = StringUtils.replaceLast(value, "}", "");
		value = StringEscapeUtils.unescapeJson(value);
		if (value.startsWith("\"")) {
			if (!value.endsWith("\"")) throw new MalformedJsonException("Quote found with no closing quote. Malformed value: '" + value + "'");
			value = value.replaceFirst("\"", "");
			StringBuilder builder = new StringBuilder(value);
			builder.setLength(builder.length() - 1);
			value = builder.toString();
			return value;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ignored) {}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException ignored) {}
		try {
			return Float.parseFloat(value);
		} catch (NumberFormatException ignored) {}
		if (value.equalsIgnoreCase("TRUE")) return true;
		if (value.equalsIgnoreCase("FALSE")) return false;
		if (value.equalsIgnoreCase("NULL")) return null;
		throw new MalformedJsonException("There was an issue parsing a value. Malformed value: '" + value + "'");
	}

	/**
	 * <strong>INTERNAL USE ONLY</strong>
	 * <p>
	 * Gets a pair with the key of a map and the parsed map.
	 *
	 * @param line the line of map, including ones that are contained.
	 * @param fullJson true if it is the whole json, false if it is a map.
	 * @return a pair containing the map key and the map contents.
	 * @throws MalformedJsonException if the JSON is malformed.
	 */
	private static Pair<String, Map<String, Object>> parseMap(String line, boolean fullJson) throws MalformedJsonException {
		Map<String, Object> currentMap = new HashMap<>();
		String key = null;
		if (!fullJson) key = line.split(":\\{")[0];
		boolean first = true;
		boolean inQuotes = false;
		boolean inArray = false;
		boolean inMap = false;
		boolean ignoreNext = false;

		if (!fullJson && line.replace(key, "").equals("{}")) return new Pair<>(key, new HashMap<>());
		else if (line.equals("{}")) return new Pair<>(key, new HashMap<>());

		int mapDepth = 0, arrayDepth = 0, index = 0;

		StringBuilder currentLine = new StringBuilder();
		String currentKey = "";
		List<Object> currentList = new ArrayList<>();

		final char[] array = line.toCharArray();

		for (char ch : array) {
			if (first) {
				first = false;
				continue;
			}
			index++;

			if (ignoreNext) {
				ignoreNext = false;
				continue;
			}

			switch (ch) {

				case '"':
					if (inQuotes && !StringUtils.isEscaped(index, array)) {
						currentLine.append(ch);
						inQuotes = false;
					} else if (!inQuotes) {
						inQuotes = true;
						currentLine
								.append(ch);
					} else currentLine.append(ch);
					break;

				case ':':
					if (!inQuotes && !inMap && inArray) throw new MalformedJsonException("Colon found in an array outside of quotes.");
					currentLine.append(ch);
					break;

				case ',':
					if (inQuotes) currentLine.append(ch);
						// Earliest , can be is at "{\"\"," (index 3)
					else if (index < 3) throw new MalformedJsonException();

					else if (inArray) {
						if (inMap) {
							currentLine.append(ch);
						} else if (currentLine.length() > 0) {
							currentList.add(StringUtils.removeQuotes(currentLine.toString()));
							currentLine.setLength(0);
						}
					} else if (inMap) currentLine.append(ch);

					else if (currentLine.length() > 0) {
						Pair<String, Object> pair = JSON.matchLine(currentLine.toString());
						currentMap.put(pair.getFirst(), pair.getSecond());
						currentLine.setLength(0);
					} //else throw new MalformedJsonException();
					break;

				case '[':
					if (!inQuotes && inArray) throw new MalformedJsonException("Opening array found inside of an array.");
					if (inQuotes) {
						currentLine.append(ch);
					} else if (inArray) {
						arrayDepth++;
					} else {
						inArray = true;
						Matcher matcher = KEY_PATTERN.matcher(currentLine.toString());
						if (matcher.matches()) currentKey = matcher.group(1);
						else throw new MalformedJsonException();
						currentLine.setLength(0);
					}
					break;

				case ']':
					if (!inQuotes && !inArray) throw new MalformedJsonException("Closing array found outside of an array.");
					if (!inQuotes) {
						if (arrayDepth == 0) {
							if (currentLine.toString().equals("[]")) currentMap.put(currentKey, new ArrayList<>());
							else {
								currentList.add(StringUtils.removeQuotes(currentLine.toString()));
								currentMap.put(currentKey, currentList);
							}
							inArray = false;
							currentList = new ArrayList<>();
							currentKey = "";
							currentLine.setLength(0);
							ignoreNext = true;
						} else arrayDepth--;
					} else currentLine.append(ch);
					break;

				case '{':
					currentLine.append(ch);
					if (!inQuotes) {
						inMap = true;
						mapDepth++;
					}
					break;

				case '}':
					currentLine.append(ch);
					if (!inQuotes) {
						if (inMap) {
							mapDepth--;
							if (mapDepth == 0) {
								if (inArray) {
									Map<String, Object> map = JSON.parseMap(currentLine.toString(), true).getSecond();
									Object fin = map;
									if (map.containsKey("-x")) {
										Class<? extends JSONSerializable> serializable = JSONSerializable.get((String) map.get("-x"));
										if (serializable != null)
											fin = JSONSerializable.deserializeHelper(serializable, map);
									}
									currentList.add(fin);
								}
								else {
									String temp = currentLine.toString().split(":\\{")[0];
									String k = StringUtils.removeQuotes(temp);
									String l = currentLine.toString().replaceFirst("\"" + k + "\":", "");
									Pair<String, Map<String, Object>> pair = JSON.parseMap(l, false);
									Map<String, Object> map = pair.getSecond();
									Object fin = map;
									if (map.containsKey("-x")) {
										Class<? extends JSONSerializable> serializable = JSONSerializable.get((String) map.get("-x"));
										if (serializable != null)
											fin = JSONSerializable.deserializeHelper(serializable, map);
									}
									currentMap.put(k, fin);
								}
								currentLine.setLength(0);
								inMap = false;
							} else if (mapDepth < 0) throw new MalformedJsonException("Closing map found outside of a map.");

						} else if (currentLine.length() != 1) {
							if (index + 1 != array.length) throw new MalformedJsonException();
							Pair<String, Object> pair = JSON.matchLine(currentLine.toString());
							currentMap.put(pair.getFirst(), pair.getSecond());
							currentLine.setLength(0);
						}
					}
					break;

				case ' ':
					if (inQuotes) currentLine.append(" ");
					break;

				default:
					currentLine.append(ch);
			}
		}
		return new Pair<>(key, currentMap);
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Constructors">
	/**
	 * Creates a new JSONWrapper object.
	 */
	public JSON() {
		super();
	}

	/**
	 * Converts a Map into a new JSONWrapper object.
	 *
	 * @param map the map to convert.
	 */
	public JSON(Map map) {
		super(map);
	}

	/**
	 * Converts a file that contains ONLY JSON content to a JSONWrapper.
	 *
	 * @param file the file to read from.
	 * @throws MalformedJsonException if the JSON is malformed.
	 * @throws IOException            if any exception is thrown.
	 */
	public JSON(File file) throws IOException, MalformedJsonException {
		this(String.join("", Files.readAllLines(file.toPath())));
	}

	/**
	 * Converts a JSON text into a JSONWrapper object.
	 *
	 * @param json the text.
	 * @throws MalformedJsonException thrown if there is an issue with the JSON.
	 */
	public JSON(String json) throws MalformedJsonException {
		if (!json.startsWith("{") && !json.endsWith("}")) throw new MalformedJsonException("JSON must start with { and end with }.");
		Pair<String, Map<String, Object>> pair = JSON.parseMap(json, true);
		this.putAll(pair.getSecond());
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Basic Public Methods">
	/**
	 * Converts the current JSONWrapper to a JSON text.
	 *
	 * @return the JSON text.
	 */
	@Override
	public String toString() {
		return JSON.toString(this, false, 0);
	}

	/**
	 * Converts the current JSONWrapper to a pretty JSON text.
	 *
	 * @return the pretty JSON text.
	 */
	public String toPrettyString() {
		return JSON.toString(this, true, 2);
	}

	/**
	 * Converts the current JSONWrapper to a pretty JSON text.
	 *
	 * @param indentation the indentation to have.
	 * @return the pretty JSON text.
	 */
	public String toPrettyString(int indentation) {
		return JSON.toString(this, true, indentation);
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Inner Classes">
	private static class StringifyJSON {

		private final String string;
		private int currentIndentation;
		private final int indentationAmount;

		public StringifyJSON(Map<?, ?> wrapper, boolean pretty, int indentation) {
			indentationAmount = indentation;
			StringBuilder builder = new StringBuilder("{" + (pretty ? "\n" + " ".repeat(indentation) : ""));
			currentIndentation = indentation;

			for (Object o : wrapper.entrySet()) {
				Entry entry = (Entry) o;
				builder.append("\"")
						.append(entry.getKey())
						.append("\"")
						.append(pretty ? ": " : ":")
						.append(this.writeJson(entry.getValue(), pretty))
						.append(",")
						.append(pretty ? "\n" : "")
						.append(pretty ? " ".repeat(currentIndentation) : "");
			}
			int clone = currentIndentation;
			builder.setLength(builder.length() - (pretty ? clone + 2 : 1));
			string = builder
					.append(pretty ? "\n" : "")
					.append("}")
					.toString();
		}
		// </editor-fold>

		// <editor-fold defaultstate="collapsed" desc="Internal Object Methods">
		/**
		 * <strong>INTERNAL USE ONLY</strong>
		 * <p>
		 * Writes a JSON string from an object.
		 *
		 * @param value the object.
		 * @return the finished JSON text.
		 */
		private String writeJson(Object value, boolean pretty) {
			StringBuilder builder = new StringBuilder();
			if (value instanceof Number number) {
				if (number instanceof Double d && d.isInfinite() && d.isNaN()) builder.append("null");
				else if (number instanceof Float f && f.isInfinite() && f.isNaN()) builder.append("null");
				else builder.append(number);
			} else if (value instanceof JSONSerializable serializable) {
				Map<String, Object> map = serializable.serialize();
				map.put("-x", serializable.getClass().getName());
				builder.append(this.writeJson(map, pretty));
			}
			else if (value instanceof Boolean bool) builder.append(bool);
			else if (value instanceof List list) builder.append(this.writeJson(list, pretty));
			else if (value instanceof Object[] array) builder.append(this.writeJson(array, pretty));
			else if (value instanceof Map map) builder.append(this.writeJson(map, pretty));
			else if (value instanceof Pair<?,?> pair) builder.append(this.writeJson((Map) pair.getSecond(), pretty));
			else if (value == null) builder.append(pretty ? " null" : "null");
			else builder.append("\"")
						.append(StringEscapeUtils.escapeJson(String.valueOf(value)))
						.append("\"");
			return builder.toString();
		}

		/**
		 * <strong>INTERNAL USE ONLY</strong>
		 * <p>
		 * Writes a JSON string from a list.
		 *
		 * @param list the list.
		 * @return the finished JSON text.
		 */
		private String writeJson(List<?> list, boolean pretty) {
			return this.writeJson(list.toArray(new Object[0]), pretty);
		}

		/**
		 * <strong>INTERNAL USE ONLY</strong>
		 * <p>
		 * Writes a JSON string from an array.
		 *
		 * @param array the array to use.
		 * @return the finished JSON text.
		 */
		private <T> String writeJson(T[] array, boolean pretty) {
			if (array.length <= 0) return "[]";
			StringBuilder builder = new StringBuilder("[");
			currentIndentation = currentIndentation + indentationAmount;
			for (Object value : array) {
				builder.append(pretty ? "\n" + " ".repeat(currentIndentation) : "")
						.append(this.writeJson(value, pretty))
						.append(",");
			}
			builder.setLength(builder.length() > 1 ? builder.length() - 1 : builder.length());
			currentIndentation = currentIndentation - indentationAmount;
			return builder.append(pretty ? "\n" + " ".repeat(currentIndentation) : "")
					.append("]")
					.toString();
		}

		/**
		 * <strong>INTERNAL USE ONLY</strong>
		 * <p>
		 * Writes a JSON string from a map.
		 *
		 * @param map the map.
		 * @return the finished JSON text.
		 */
		private String writeJson(Map<?, ?> map, boolean pretty) {
			if (map.isEmpty()) return "{}";
			currentIndentation = currentIndentation + indentationAmount;
			StringBuilder builder = new StringBuilder("{" + (pretty ? "\n" + " ".repeat(currentIndentation) : ""));
			for (Object o : map.entrySet()) {
				Entry entry = (Entry) o;
				builder.append("\"")
						.append(entry.getKey())
						.append("\"")
						.append(": ");
				builder.append(this.writeJson(entry.getValue(), pretty))
						.append(",")
						.append(pretty ? "\n" + " ".repeat(currentIndentation) : "");
			}
			int clone = currentIndentation;
			builder.setLength(builder.length() > 1 ? builder.length() - (pretty ? clone + 2 : 1) : builder.length());
			currentIndentation = currentIndentation - indentationAmount;
			builder.append(pretty ? "\n" + " ".repeat(currentIndentation) + "}" : "}");
			return builder.toString();
		}

		public String toString() {
			return string;
		}
	}
	// </editor-fold>

}