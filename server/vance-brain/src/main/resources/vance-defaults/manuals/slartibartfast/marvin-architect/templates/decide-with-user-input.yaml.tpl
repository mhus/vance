name: {{ params.name }}
description: |
  {{ params.description | yamlIndent(2) }}
engine: marvin
params:
  model: default:analyze
  availableRecipes:
{%- for r in params.availableRecipes %}
    - {{ r }}
{%- endfor %}
  language: {{ params.language }}
promptPrefix: |
  Du sollst eine Entscheidung treffen, brauchst dafür aber zuerst
  Eingaben vom User.

  Vorgehen:
  - Stelle dem User in einem oder mehreren NEEDS_USER_INPUT-Turns
    folgende Fragen:
{%- for q in params.questions %}
    - {{ q.title }} ({{ q.type | default('FEEDBACK') }}): {{ q.body | yamlIndent(6) }}{% if q.options is not null and q.options is not empty %}
      Optionen: {{ q.options }}{% endif %}
{%- endfor %}
  - Du darfst die Fragen auch sequenziell stellen (eine nach der
    anderen via NEEDS_USER_INPUT in jeweils eigenen SCOPE/REFLECT-
    Turns) oder alle auf einmal via NEEDS_SUBTASKS mit
    USER_INPUT-Kindern — je nachdem, ob die spätere Frage von der
    Antwort auf die frühere abhängt.
  - Wenn alle Antworten vorliegen: {{ params.decisionPrompt | yamlIndent(4) }}.
  - Sprache: {{ params.language }}.

  Wenn deine Entscheidung fertig ist (in CONCLUDE), gib
  zusätzlich folgende postActions zurück, damit das Ergebnis
  persistiert wird:
    [
      {"tool":"doc_write_text",
       "args":{
         "path":"{{ params.outputPathTpl }}",
         "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"{% if params.outputTitleTpl %},
         "title":"{{ params.outputTitleTpl }}"{% endif %}}}
    ]
