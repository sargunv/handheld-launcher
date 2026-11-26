#!/usr/bin/env bash
# Prints what the debug app shows on the connected device, on every display, so
# a tool can check where it is without a screenshot: the focused element first,
# then each display's elements, with their test tags, text, accessibility
# labels, states and bounds in that display's pixels.
#
# It reads `uiautomator dump --windows`. An element with a test tag (shown as a
# resource id) or that takes focus is one line, with the text inside it; other
# text gets a line of its own.
set -euo pipefail

package=dev.sargunv.handheld
dump="$(mktemp)"
trap 'rm -f "$dump"' EXIT

# The device's own mktemp keeps sessions that share it apart too.
# shellcheck disable=SC2016 # Expanded by the device's shell.
adb exec-out sh -c 'f="$(mktemp -p /data/local/tmp)" && uiautomator dump --windows "$f" >/dev/null && cat "$f"; rm -f "$f"' >"$dump"

if ! grep -q "package=\"$package\"" "$dump"; then
  echo "The app shows nothing that uiautomator can see. Is it running?" >&2
  exit 1
fi

awk -v RS='<' -v package="$package" '
function attr(name,   re) {
  re = " " name "=\"[^\"]*\""
  if (!match($0, re)) return ""
  return unescape(substr($0, RSTART + length(name) + 3, RLENGTH - length(name) - 4))
}
function unescape(s) {
  gsub(/&#1[03];/, " ", s)
  gsub(/&lt;/, "<", s); gsub(/&gt;/, ">", s); gsub(/&quot;/, "\"", s)
  gsub(/&apos;/, "\x27", s); gsub(/&amp;/, "\\&", s)
  gsub(/  +/, " ", s)
  return s
}
function words(s, add) { return s == "" ? add : (add == "" ? s : s " " add) }
function line(i,   out, flags) {
  out = tag[i] == "" ? "" : tag[i] " "
  if (kind[i] != "") out = out kind[i] " "
  if (text[i] != "") out = out "\"" text[i] "\" "
  if (desc[i] != "" && desc[i] != text[i]) out = out "desc=\"" desc[i] "\" "
  flags = ""
  if (focused[i]) flags = flags " focused"
  if (selected[i]) flags = flags " selected"
  if (checked[i]) flags = flags " checked"
  if (disabled[i]) flags = flags " disabled"
  if (flags != "") out = out "(" substr(flags, 2) ") "
  return out bounds[i]
}
/^display / { display = attr("id"); next }
/^window / { title = attr("title"); depth = 0; owner[0] = 0; next }
/^\/node>/ { depth--; next }
/^node / {
  selfClosing = $0 ~ /\/>[[:space:]]*$/
  mine = 0
  if (attr("package") == package) {
    rid = attr("resource-id"); t = attr("text"); d = attr("content-desc")
    isOwner = (rid != "" && rid !~ /^android:/) || attr("focusable") == "true"
    if (isOwner || (owner[depth] == 0 && (t != "" || d != ""))) {
      i = ++n
      mine = isOwner ? i : 0
      if (!(display in seen)) { seen[display] = 1; displays[++displayCount] = display }
      where[i] = display; windowTitle[display] = title
      tag[i] = rid ~ /^android:/ ? "" : rid
      text[i] = t; desc[i] = d; bounds[i] = attr("bounds")
      kind[i] = attr("class"); sub(/.*\./, "", kind[i])
      if (kind[i] == "View" || kind[i] == "TextView") kind[i] = ""
      focused[i] = attr("focused") == "true"
      selected[i] = attr("selected") == "true"
      checked[i] = attr("checked") == "true"
      disabled[i] = attr("enabled") == "false"
      if (focused[i]) focus = i
    } else if (owner[depth] != 0) {
      # Text inside an element belongs to it, as a screen reader reads it.
      o = owner[depth]
      text[o] = words(text[o], t != "" ? t : d)
    }
  }
  if (!selfClosing) {
    owner[depth + 1] = mine != 0 ? mine : owner[depth]
    depth++
  }
}
END {
  if (focus) printf "Focused on display %s: %s\n", where[focus], line(focus)
  else print "Focused: nothing in the app"
  for (k = 1; k <= displayCount; k++) {
    shown = displays[k]
    name = windowTitle[shown] == "" ? "" : " (" windowTitle[shown] ")"
    printf "\nDisplay %s%s\n", shown, name
    for (i = 1; i <= n; i++) if (where[i] == shown) print "  " line(i)
  }
}
' "$dump"
