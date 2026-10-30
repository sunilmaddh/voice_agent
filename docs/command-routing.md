# Local command routing

SEND_MESSAGE now creates a validated [SMS compose handoff](core-actions.md).
Names require user selection in the messaging app; actual sending is not automated.

Update: the voice coordinator now executes validated NAVIGATE and OPEN_MAPS
proposals through the [Maps executor](maps-launch.md), and GOOGLE_SEARCH through
the [Google Search executor](google-search.md). Calendar queries, local reminders,
calendar opening and event drafts now use the [calendar integration](calendar-reminders.md).
READ_EMAIL and REPLY_EMAIL now use the [Gmail integration](gmail-integration.md);
REPLY_EMAIL creates a review draft only. Sending requires a separate single-use
confirmation from the Gmail screen. The router and model
benchmark still only return proposals; other external actions remain disabled.

This document records the Tier 1 milestone. Tier 2 is now implemented through an
optional local model adapter; see [Tier 2 setup, validation and benchmarks](tier2-command-model.md).
Unconfigured devices still return MODEL_NOT_CONFIGURED. Tier 3 remains unconfigured.

Navigation also accepts `take me to <destination>` in Tier 1. For example,
`take me to bangalore airport.` produces a NAVIGATE proposal without loading an
SLM. Ambiguous destinations and compound commands still require clarification.
This does not launch Maps; external action execution remains unimplemented.

The final STT transcript now passes through a native IntentRouter before TTS speaks
the routing outcome. No ActionExecutor is connected. The existing TTS-only preview
bypasses routing. Speech errors/cancellation retain their previous handling.

## Three tiers

1. Deterministic: complete, explicit grammar -> typed command -> independent schema,
   confidence and semantic validation -> inert action proposal or clarification.
2. Command model: command-like input outside the grammar -> MODEL_NOT_CONFIGURED,
   UNKNOWN intent, modelRequired=true, no action proposal.
3. General local model: document/general questions -> DOCUMENT_QUERY/GENERAL_QUERY,
   MODEL_NOT_CONFIGURED, modelRequired=true, no action proposal.

No SLM is loaded, selected, downloaded or invoked in any tier. The existing
CommandModel/GeneralAssistantModel interfaces remain the future integration ports.
LocalIntentRouter deliberately has no model or ActionExecutor dependency, ensuring
deterministic routing cannot invoke either. A future model stage must produce typed
commands and pass the validator/context/confirmation checks before execution can be
considered; it must not treat the routing decision as execution authorization.

## Typed schemas

Kotlin sealed TypedCommand subclasses define the parameter shape. AssistantIntent is
the serializable envelope and CommandValidator independently checks externally
constructed envelopes. Dart recognizes the canonical wire identifiers.

| Intent | Parameters |
|---|---|
| NAVIGATE | destination |
| GOOGLE_SEARCH | query |
| CREATE_REMINDER | title, dueEpochMs, timeZoneId |
| SEND_MESSAGE | recipientName, body, channel (SMS/UNSPECIFIED) |
| QUERY_CALENDAR | startEpochMs inclusive, endEpochMs exclusive, timeZoneId |
| OPEN_MAPS | none |
| OPEN_CALENDAR | none |
| STOP_NAVIGATION | none |
| READ_NOTIFICATION | scope: LATEST/ALL_ACTIVE |
| READ_WHATSAPP | scope: LATEST |
| REPLY_WHATSAPP | body; conversation identity must be resolved later |
| READ_EMAIL | scope: LATEST |
| REPLY_EMAIL | body; message/thread identity must be resolved later |
| DOCUMENT_QUERY | question; document context must be supplied later |
| GENERAL_QUERY | question |
| UNKNOWN | none |

Supported examples:

- Open Maps; open Google Calendar; stop navigation.
- Navigate to Bangalore Airport.
- Search Google for restaurants near MG Road.
- Remind me tomorrow at 10 AM to call Rahul.
- What meetings do I have tomorrow? Show my calendar today.
- Read my latest notification; read notifications; read my latest WhatsApp message;
  read my latest email.
- Tell Rahul that I will call later; send SMS to Rahul saying I will call later.
- Reply WhatsApp that I will join at four; reply email that four works for me.

Messaging examples produce typed intents but **no action proposal** because recipient,
channel/reply-context resolution and confirmation have not been implemented.
Recognizing STOP_NAVIGATION establishes syntax only, not third-party API support.

## Confidence and safety

Exact command aliases receive rule confidence 1.0; successful slot grammars receive
0.98. These are fixed engineering scores, not calibrated speech/model probabilities.
An executable-shaped proposal requires confidence >=0.9 and valid parameters. Tier
classification confidence for a general question is not permission to execute it.

The parser anchors complete commands, preserves captured parameter capitalization,
normalizes whitespace/case for matching, and accepts a leading “please” and terminal
sentence punctuation. Empty, oversized/control-character, negated, conditional and
recognized multi-command input is rejected or requires clarification. Missing fields,
unresolved pronouns, uncertain reminder times and unknown requests produce no action.
The grammar is intentionally bounded: unsupported paraphrases go to the deferred
command tier instead of being guessed.

Reminder dates: today, tomorrow or YYYY-MM-DD. Times: explicit AM/PM, or two-digit
24-hour HH:mm. Date conversion uses an injected clock and device time zone; past dates,
invalid calendar values, DST gaps and common DST overlaps are rejected. Calendar day
ranges use local calendar arithmetic, including 23/25-hour days. Relative/recurring
phrases such as “next Friday,” “later,” or “every week” are not parsed in this milestone.

No reminder, navigation, message, email, calendar read or document operation is
performed. The response's executed-action field remains null and action latency stays
not_run, even when a valid proposal exists. Future external integrations must add
permission, capability, recipient, confirmation and lock-state checks independently.

## Diagnostics

Responses/UI include selected tier, canonical intent, structured parameters,
confidence, routing reason/status, modelRequired, modelSelected and execution mode.
`routing.proposedAction` is visibly labelled **not executed**.
`routing.actionExecuted` is always false. `action` remains null.

Routing executes locally, so its execution mode is LOCAL. Model execution remains
NOT_RUN, selectedModel is null, and SLM latency stays not_run. Routing latency is
measured independently from STT/TTS. No raw model output is consumed; no request text
or parameters are added to persistent structured logs.

## Changed files

- Native intent package: TypedCommand, LocalIntentRouter, CommandTimeParser,
  CommandValidator and expanded IntentRouter/RoutedIntent contracts.
- Native Models and SpeechCoordinator: canonical intents and route diagnostics;
  routing inserted after final STT and before TTS.
- Flutter contract models and assistant_screen: canonical intent decoding and
  routing/proposal/confidence/model diagnostics.
- Tests: IntentRouterTest, SpeechCoordinatorTest, bridge_test and widget_test.
- Documentation: this file, README and speech-layer guide.

Remaining work: command/general model adapters, explicit conversation/document
context, calibrated accuracy evaluation, broader English grammar, and external
action integrations. None is automatically enabled by this milestone.

## Verification

- Flutter static analysis: no issues.
- Flutter tests: 15 passed.
- Kotlin unit tests: 21 passed, including 9 routing tests.
- Android debug assembly: passed.
- Android lint: 0 errors, 2 existing warnings (dependency update and redundant
  version-qualified drawable resource).

Routing has been verified with automated tests. This milestone does not establish
physical-device speech accuracy or external-action feasibility.

SMS additions: READ_SMS (scope LATEST) and REPLY_SMS (body). Examples: Read my latest SMS; Reply to SMS saying I will call you later. Tier 1; no SLM. Reply uses an exact, recently read message and opens an SMS composer. See SMS.md.


Incoming call reply: REPLY_TO_CALL with fixed body I will call you later. Explicit phrase: Reply to caller saying I will call you later. Tier 1 opens review for exactly one ringing call; no immediate send. See INCOMING_CALL_REPLY.md.

