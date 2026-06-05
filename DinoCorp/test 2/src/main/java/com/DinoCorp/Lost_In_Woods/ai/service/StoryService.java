package com.DinoCorp.Lost_In_Woods.ai.service;

import com.DinoCorp.Lost_In_Woods.ai.dto.ChoiceView;
import com.DinoCorp.Lost_In_Woods.ai.dto.StoryResponse;
import com.DinoCorp.Lost_In_Woods.ai.dto.TraitView;
import com.DinoCorp.Lost_In_Woods.ai.provider.AIProvider;
import com.DinoCorp.Lost_In_Woods.ai.provider.AIProvider.ChatMessagePayload;
import com.DinoCorp.Lost_In_Woods.exception.ResourceNotFoundException;
import com.DinoCorp.Lost_In_Woods.model.GameSession;
import com.DinoCorp.Lost_In_Woods.repository.GameSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

// Drives the endless AI-controlled story. Holds each run's transcript in memory,
// asks the LLM (the game master) for the next beat after every choice, and tracks
// the player's health/score/traits/events so the leaderboard score can be computed.
@Service
public class StoryService {

	// Trait scoring weights (see the prompt's TRAIT scoring rules).
	private static final int GOOD_TRAIT_POINTS = 5;
	private static final int BAD_TRAIT_SURVIVED = 1;
	private static final int BAD_TRAIT_FATAL = -3;

	// Cap how much transcript we resend each turn (system prompt + most recent
	// messages) so input tokens — and latency — don't grow as the run goes on.
	private static final int MAX_CONTEXT_MESSAGES = 12;

	private final AIProvider aiProvider;
	private final GameSessionRepository sessionRepository;
	private final ObjectMapper objectMapper = new ObjectMapper();

	// Transcript per session. In-memory: a playthrough lives for one server run.
	private final Map<Long, List<ChatMessagePayload>> transcripts = new ConcurrentHashMap<>();

	// Last successfully-parsed beat per session — used to preserve the run's state
	// (health/score/traits/items) if a reply comes back as unparseable JSON.
	private final Map<Long, Beat> lastBeat = new ConcurrentHashMap<>();

	// Generic choices shown only on the rare turn a beat can't be parsed.
	private static final List<ChoiceView> FALLBACK_CHOICES = List.of(
			new ChoiceView("Press deeper into the woods", ""),
			new ChoiceView("Stop and listen to the dark", ""),
			new ChoiceView("Search your surroundings", ""),
			new ChoiceView("Move quickly and quietly", ""));

	public StoryService(AIProvider aiProvider, GameSessionRepository sessionRepository) {
		this.aiProvider = aiProvider;
		this.sessionRepository = sessionRepository;
	}

	// Begin the endless story for an existing session: seed the prompt (with the
	// player's character description, if any) and ask for the opening beat.
	public StoryResponse start(Long sessionId, String appearance, List<String> startingItems) {
		GameSession session = requireSession(sessionId);
		seedTranscript(sessionId, buildSystemForStart(appearance, startingItems));
		return generate(sessionId, session, false, null);
	}

	// Streaming variant of start(): builds the transcript identically, then streams
	// narrative deltas to the sink while the opening beat is generated.
	public StoryResponse startStream(Long sessionId, String appearance, List<String> startingItems,
									 Consumer<String> narrativeSink) {
		GameSession session = requireSession(sessionId);
		String system = buildSystemForStart(appearance, startingItems);
		seedTranscript(sessionId, system);
		return generate(sessionId, session, false, narrativeSink);
	}

	private String buildSystemForStart(String appearance, List<String> startingItems) {
		String system = GameMasterPrompt.SYSTEM_PROMPT;
		if (appearance != null && !appearance.isBlank()) {
			system += "\n\n### PLAYER CHARACTER\nThe survivor is " + appearance
					+ " Weave their appearance into the narration naturally when it fits.";
		}
		if (startingItems != null) {
			if (startingItems.isEmpty()) {
				system += "\n\n### STARTING INVENTORY\nThe survivor begins this run with NOTHING in hand:"
						+ " set the opening beat's \"items\" to an EMPTY list. They must scavenge for any gear in the forest.";
			} else {
				system += "\n\n### STARTING INVENTORY\nThe survivor begins this run carrying EXACTLY these items: "
						+ String.join(", ", startingItems)
						+ ". Set the opening beat's \"items\" to this exact list and acknowledge the gear naturally in the narration.";
			}
		}
		return system;
	}

	private void seedTranscript(Long sessionId, String system) {
		List<ChatMessagePayload> messages = new ArrayList<>();
		messages.add(new ChatMessagePayload("system", system));
		messages.add(new ChatMessagePayload("user", "Begin the run: the opening beat."));
		transcripts.put(sessionId, messages);
	}

	// Apply the player's choice and ask the AI for the next beat.
	public StoryResponse choose(Long sessionId, String choiceText) {
		GameSession session = requireSession(sessionId);
		if (session.isGameOver()) return terminal(session);
		appendChoiceMessage(sessionId, choiceText);
		return generate(sessionId, session, true, null);
	}

	// Streaming variant of choose(): same setup, but pipes narrative deltas as they
	// arrive from the model. Returns the full StoryResponse once generation finishes.
	public StoryResponse chooseStream(Long sessionId, String choiceText, Consumer<String> narrativeSink) {
		GameSession session = requireSession(sessionId);
		if (session.isGameOver()) return terminal(session);
		appendChoiceMessage(sessionId, choiceText);
		return generate(sessionId, session, true, narrativeSink);
	}

	// Build and add the user message for a choice. Includes the inventory anchor
	// and the action-specific item-consumption reminders the prompt relies on.
	private void appendChoiceMessage(Long sessionId, String choiceText) {
		List<ChatMessagePayload> messages = transcripts.computeIfAbsent(sessionId, id -> {
			List<ChatMessagePayload> seed = new ArrayList<>();
			seed.add(new ChatMessagePayload("system", GameMasterPrompt.SYSTEM_PROMPT));
			return seed;
		});
		Beat prev = lastBeat.get(sessionId);
		String inventoryLine = (prev == null || prev.items() == null || prev.items().isEmpty())
				? "Current inventory: (empty — the player carries nothing)."
				: "Current inventory: " + String.join(", ", prev.items()) + ". Do NOT reference items outside this list.";
		String lower = choiceText == null ? "" : choiceText.toLowerCase();
		String reminders = "";
		if (matchAny(lower, "trade","give","offer","exchange","barter","hand over","deal","bargain"))
			reminders += " TRADE REMINDER: if the narration describes the player handing an item to an NPC, that item MUST be REMOVED from \"items\" this beat.";
		if (matchAny(lower, "shoot","fire","arrow","loose","nock","draw bow","quiver","aim"))
			reminders += " ARROW REMINDER: if an arrow is shot or fired, \"Quiver (arrows)\" MUST be REMOVED from \"items\".";
		if (matchAny(lower, "crowbar","pry","wedge","lever","force open","break open"))
			reminders += " CROWBAR REMINDER: crowbar is permanent — using it does NOT remove it from \"items\".";
		if (matchAny(lower, "bandage","bind","wrap","dress","tend","patch"))
			reminders += " BANDAGE REMINDER: bandages are consumed on use — REMOVE from \"items\" this beat.";
		messages.add(new ChatMessagePayload("user",
				"The player chose: " + (choiceText == null ? "" : choiceText.trim()) + ". "
						+ inventoryLine + reminders + " Continue the story and provide the next beat."));
	}

	private boolean matchAny(String s, String... needles) {
		for (String n : needles) if (s.contains(n)) return true;
		return false;
	}

	private StoryResponse generate(Long sessionId, GameSession session, boolean advance, Consumer<String> narrativeSink) {
		List<ChatMessagePayload> messages = transcripts.get(sessionId);
		String raw;
		if (narrativeSink == null) {
			raw = aiProvider.ask(new AIProvider.ChatRequest(contextWindow(messages))).message();
		} else {
			NarrativeStreamer streamer = new NarrativeStreamer(narrativeSink);
			raw = aiProvider.askStream(new AIProvider.ChatRequest(contextWindow(messages)), streamer).message();
		}
		messages.add(new ChatMessagePayload("assistant", raw));

		Beat beat = parse(raw);
		if (beat == null) {
			// Reply wasn't valid JSON — ask once more for a clean object before giving up.
			messages.add(new ChatMessagePayload("system",
					"Your previous reply was not valid JSON. Resend the SAME beat as ONE valid JSON object only — "
							+ "no markdown, no commentary, and make sure every object is wrapped in { }."));
			String retry = aiProvider.ask(new AIProvider.ChatRequest(contextWindow(messages))).message();
			messages.add(new ChatMessagePayload("assistant", retry));
			beat = parse(retry);
		}
		if (beat == null) {
			// Still unparseable: preserve the run's state instead of wiping it.
			Beat prev = lastBeat.get(sessionId);
			beat = new Beat(
					prev != null ? prev.location() : "dense_forest",
					prev != null ? prev.npc() : "",
					prev != null ? prev.stance() : "",
					prev != null ? prev.survivorStance() : "",
					"For a heartbeat the world blurs and slips sideways. You blink it away and steady yourself, the woods pressing close.",
					FALLBACK_CHOICES,
					session.getCurrentHealth(),
					session.getCurrentScore(),
					prev != null ? prev.traits() : List.of(),
					prev != null ? prev.items() : List.of(),
					"continue", null);
		} else {
			lastBeat.put(sessionId, beat);
		}

		// Health is authoritative for death: the player dies only when hp reaches 0.
		String endingType = null;
		if (advance) {
			// Health/score only change once the player starts making choices.
			// The opening beat keeps the full starting health (100) and score (0).
			int hp = Math.max(0, Math.min(100, beat.hp()));
			session.setCurrentHealth(hp);
			session.setCurrentScore(beat.score());

			boolean died = hp <= 0;                                  // death is hp-authoritative
			boolean reachedEnding = !died && isSurvivalEnding(beat.outcome());
			if (!died) {
				session.setEventsSurvived(session.getEventsSurvived() + 1);
			}
			if (died || reachedEnding) {
				session.setGameOver(true);
				if (died) {
					session.setCurrentHealth(0);
					session.setEnding(nonBlank(beat.ending()) ? beat.ending() : "You Died");
					// Fatal run: bad traits are penalized.
					session.setFinalScore(beat.score() + traitPoints(beat.traits(), true));
					endingType = "death";
				} else {
					// Survived ending (escape/transformation/lost/secret): bad traits add +1.
					session.setEnding(nonBlank(beat.ending()) ? beat.ending() : endingTitle(beat.outcome()));
					session.setFinalScore(beat.score() + session.getCurrentHealth() + traitPoints(beat.traits(), false));
					endingType = beat.outcome().toLowerCase();
				}
			}
		}
		sessionRepository.save(session);

		return new StoryResponse(
				session.getId(),
				beat.location(),
				beat.npc(),
				beat.stance(),
				beat.survivorStance(),
				beat.narrative(),
				session.isGameOver() ? List.of() : beat.choices(),
				session.isGameOver(),
				session.getEnding(),
				endingType,
				session.getCurrentHealth(),
				session.getCurrentScore(),
				beat.traits(),
				beat.items(),
				session.getEventsSurvived(),
				session.getFinalScore());
	}

	// finalScore trait contribution. Endless runs end in death, so bad traits are penalized.
	private int traitPoints(List<TraitView> traits, boolean fatal) {
		int total = 0;
		for (TraitView t : traits) {
			if (!t.bad()) {
				total += GOOD_TRAIT_POINTS;
			} else {
				total += fatal ? BAD_TRAIT_FATAL : BAD_TRAIT_SURVIVED;
			}
		}
		return total;
	}

	// How many of the MOST RECENT assistant beats are kept raw (full JSON) in the
	// context window. Older assistant turns get compressed to a tiny state record
	// (location/npc/items/hp) — that's enough for continuity but ~10x smaller, so
	// every request after the first few beats spends far fewer input tokens.
	private static final int KEEP_RECENT_RAW = 2;

	private List<ChatMessagePayload> contextWindow(List<ChatMessagePayload> messages) {
		// First trim to the last MAX_CONTEXT_MESSAGES messages (plus system at index 0).
		List<ChatMessagePayload> base;
		if (messages.size() <= MAX_CONTEXT_MESSAGES) {
			base = messages;
		} else {
			base = new ArrayList<>(MAX_CONTEXT_MESSAGES);
			base.add(messages.get(0));
			base.addAll(messages.subList(messages.size() - (MAX_CONTEXT_MESSAGES - 1), messages.size()));
		}

		// Count assistant messages in the window so we know which ones are "recent".
		int assistantSeen = 0;
		int totalAssistants = 0;
		for (ChatMessagePayload m : base) if ("assistant".equals(m.role())) totalAssistants++;

		// Walk and rewrite: keep the last KEEP_RECENT_RAW assistant turns raw; for
		// older assistants, replace their JSON content with a short state summary.
		List<ChatMessagePayload> trimmed = new ArrayList<>(base.size());
		for (ChatMessagePayload m : base) {
			if (!"assistant".equals(m.role())) { trimmed.add(m); continue; }
			assistantSeen++;
			boolean isRecent = (totalAssistants - assistantSeen) < KEEP_RECENT_RAW;
			if (isRecent) { trimmed.add(m); continue; }
			String summary = summarizeAssistant(m.content());
			trimmed.add(summary == null ? m : new ChatMessagePayload("assistant", summary));
		}
		return trimmed;
	}

	// Squash a past beat's full JSON down to just the run-state fields the model
	// needs to maintain continuity: location, npc, items, hp. Drops the narrative,
	// choices, traits, stances, etc. which are no longer useful as context.
	private String summarizeAssistant(String raw) {
		try {
			JsonNode n = objectMapper.readTree(extractJson(raw));
			StringBuilder sb = new StringBuilder("{\"location\":\"")
					.append(n.path("location").asText("dense_forest"))
					.append("\",\"npc\":\"").append(n.path("npc").asText(""))
					.append("\",\"hp\":").append(n.path("hp").asInt(100))
					.append(",\"items\":[");
			if (n.has("items") && n.get("items").isArray()) {
				boolean first = true;
				for (JsonNode it : n.get("items")) {
					if (!first) sb.append(',');
					sb.append('"').append(it.asText().replace("\"", "\\\"")).append('"');
					first = false;
				}
			}
			sb.append("]}");
			return sb.toString();
		} catch (Exception e) {
			return null; // can't summarize — leave raw
		}
	}

	private StoryResponse terminal(GameSession session) {
		return new StoryResponse(session.getId(), "dense_forest", "", "", "", "This run has already ended.",
				List.of(), true, session.getEnding(), null, session.getCurrentHealth(), session.getCurrentScore(),
				List.of(), List.of(), session.getEventsSurvived(), session.getFinalScore());
	}

	// Non-death endings (Epic 11). Death is handled separately via hp.
	private boolean isSurvivalEnding(String outcome) {
		return switch (outcome == null ? "" : outcome.toLowerCase()) {
			case "escape", "transformation", "lost", "secret", "ending" -> true;
			default -> false;
		};
	}

	private String endingTitle(String outcome) {
		return switch (outcome == null ? "" : outcome.toLowerCase()) {
			case "escape" -> "You Escaped the Woods";
			case "transformation" -> "You Were Transformed";
			case "lost" -> "Lost Forever";
			case "secret" -> "A Secret Fate";
			default -> "The End";
		};
	}

	private boolean nonBlank(String s) {
		return s != null && !s.isBlank();
	}

	private GameSession requireSession(Long sessionId) {
		if (sessionId == null) {
			throw new ResourceNotFoundException("Session not found.");
		}
		return sessionRepository.findById(sessionId)
				.orElseThrow(() -> new ResourceNotFoundException("Session not found."));
	}

	private Beat parse(String raw) {
		try {
			JsonNode node = objectMapper.readTree(extractJson(raw));
			String location = node.path("location").asText("dense_forest");
			String npc = node.path("npc").asText("");
			String stance = node.path("stance").asText("");
			String survivorStance = node.path("survivor_stance").asText("");
			String narrative = node.path("narrative").asText("");
			String outcome = node.path("outcome").asText("continue");
			String ending = node.hasNonNull("ending") ? node.get("ending").asText() : null;
			int hp = node.path("hp").asInt(100);
			int score = node.path("score").asInt(0);

			List<ChoiceView> choices = new ArrayList<>();
			if (node.has("choices") && node.get("choices").isArray()) {
				for (JsonNode c : node.get("choices")) {
					if (c.isObject()) {
						choices.add(new ChoiceView(c.path("text").asText(""), c.path("trait").asText("")));
					} else if (c.isTextual()) {
						choices.add(new ChoiceView(c.asText(), ""));
					}
				}
			}

			List<TraitView> traits = new ArrayList<>();
			if (node.has("traits") && node.get("traits").isArray()) {
				for (JsonNode t : node.get("traits")) {
					if (t.isObject()) {
						traits.add(new TraitView(t.path("name").asText(""), t.path("bad").asBoolean(false)));
					} else if (t.isTextual()) {
						traits.add(new TraitView(t.asText(), false));
					}
				}
			}

			List<String> items = new ArrayList<>();
			if (node.has("items") && node.get("items").isArray()) {
				node.get("items").forEach(i -> items.add(i.asText()));
			}

			while (traits.size() > 6) traits.remove(traits.size() - 1);
			while (items.size() > 6) items.remove(items.size() - 1);
			return new Beat(location, npc, stance, survivorStance, narrative, choices, hp, score, traits, items, outcome, ending);
		}
		catch (Exception e) {
			return null; // unparseable — let generate() preserve the run's state
		}
	}

	// Tolerate models that wrap JSON in markdown fences or add stray text.
	private String extractJson(String raw) {
		String s = raw == null ? "" : raw.trim();
		if (s.startsWith("```")) {
			int newline = s.indexOf('\n');
			if (newline >= 0) {
				s = s.substring(newline + 1);
			}
			if (s.endsWith("```")) {
				s = s.substring(0, s.length() - 3);
			}
		}
		int start = s.indexOf('{');
		int end = s.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return s.substring(start, end + 1);
		}
		return s;
	}

	// Parsed view of one AI beat.
	private record Beat(String location, String npc, String stance, String survivorStance, String narrative, List<ChoiceView> choices, int hp, int score,
						List<TraitView> traits, List<String> items, String outcome, String ending) {
	}

	// Consumes streamed token chunks from the AI provider, watches for the
	// "narrative":"…" field in the growing JSON, and forwards each new decoded
	// piece of that field's text to a downstream sink (the HTTP response writer).
	// Other JSON fields (choices, traits, items, etc.) are NOT streamed — they
	// arrive together as part of the final non-streaming parse, so the client gets
	// a complete StoryResponse at the end.
	//
	// This is intentionally a forgiving, manual JSON scan rather than a full
	// streaming parser: it handles the common case (well-formed JSON, no surprises
	// before the narrative field) and degrades gracefully when content arrives
	// faster than we can resolve a JSON escape — we just wait for more bytes.
	static final class NarrativeStreamer implements Consumer<String> {
		private final StringBuilder cumulative = new StringBuilder(4096);
		private final Consumer<String> sink;
		private int valueStart = -1;     // index of first char after the opening quote
		private int emitted = 0;          // chars of decoded narrative we've already sent

		NarrativeStreamer(Consumer<String> sink) { this.sink = sink; }

		@Override
		public void accept(String chunk) {
			if (chunk == null || chunk.isEmpty()) return;
			cumulative.append(chunk);
			if (valueStart < 0) {
				int marker = cumulative.indexOf("\"narrative\"");
				if (marker < 0) return;
				int colon = cumulative.indexOf(":", marker + 11);
				if (colon < 0) return;
				int quote = -1;
				for (int i = colon + 1; i < cumulative.length(); i++) {
					char c = cumulative.charAt(i);
					if (c == '"') { quote = i; break; }
					if (!Character.isWhitespace(c)) return; // unexpected — give up streaming
				}
				if (quote < 0) return;
				valueStart = quote + 1;
			}
			emitDecoded();
		}

		// Walk from valueStart, decoding JSON escapes, building the live narrative
		// string, and emitting any portion not yet sent. Stops at the unescaped
		// closing quote (after which no more narrative will arrive).
		private void emitDecoded() {
			StringBuilder decoded = new StringBuilder();
			int i = valueStart;
			while (i < cumulative.length()) {
				char c = cumulative.charAt(i);
				if (c == '\\') {
					if (i + 1 >= cumulative.length()) break; // need next char
					char esc = cumulative.charAt(i + 1);
					switch (esc) {
						case 'n':  decoded.append('\n'); break;
						case 't':  decoded.append('\t'); break;
						case 'r':  decoded.append('\r'); break;
						case '"':  decoded.append('"');  break;
						case '\\': decoded.append('\\'); break;
						case '/':  decoded.append('/');  break;
						case 'u':
							if (i + 5 >= cumulative.length()) return; // need 4 hex digits
							try {
								int cp = Integer.parseInt(cumulative.substring(i + 2, i + 6), 16);
								decoded.append((char) cp);
							} catch (NumberFormatException ignored) { decoded.append('?'); }
							i += 4;
							break;
						default: decoded.append(esc); break;
					}
					i += 2;
				} else if (c == '"') {
					break; // end of the narrative string — stop streaming
				} else {
					decoded.append(c);
					i++;
				}
			}
			if (decoded.length() > emitted) {
				String fresh = decoded.substring(emitted);
				emitted = decoded.length();
				try { sink.accept(fresh); } catch (Exception ignore) { /* client gone */ }
			}
		}
	}
}
