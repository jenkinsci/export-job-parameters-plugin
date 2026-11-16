// Colors the generated code. Only wraps tokens in spans, the text of the block stays the same.
document.addEventListener("DOMContentLoaded", function () {
  var token =
    /('''(?:\\[\s\S]|[^\\])*?'''|'(?:\\.|[^'\\\n])*'|"(?:\\.|[^"\\\n])*")|(\/\/[^\n]*)|\b(true|false|null|\d+(?:\.\d+)?)\b|([A-Za-z_$][\w$]*)(?=\(| \{)/g;
  var kinds = [null, "string", "comment", "literal", "call"];

  document.querySelectorAll(".export-parameters-code pre").forEach(function (pre) {
    var text = pre.textContent;
    var highlighted = document.createDocumentFragment();
    var last = 0;
    var match;
    token.lastIndex = 0;
    while ((match = token.exec(text)) !== null) {
      highlighted.appendChild(document.createTextNode(text.slice(last, match.index)));
      var span = document.createElement("span");
      for (var group = 1; group < kinds.length; group++) {
        if (match[group] !== undefined) {
          span.className = "export-parameters-" + kinds[group];
        }
      }
      span.textContent = match[0];
      highlighted.appendChild(span);
      last = match.index + match[0].length;
    }
    highlighted.appendChild(document.createTextNode(text.slice(last)));
    pre.textContent = "";
    pre.appendChild(highlighted);
  });
});
