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
	public StoryResponse start(Long sessionId, String appearance) {
		GameSession session = requireSession(sessionId);
		String system = GameMasterPrompt.SYSTEM_PROMPT;
		if (appearance != null && !appearance.isBlank()) {
			system = system + "\n\n### PLAYER CHARACTER\nThe survivor is " + appearance
					+ " Weave their appearance into the narration naturally when it fits.";
		}
		List<ChatMessagePayload> messages = new ArrayList<>();
		messages.add(new ChatMessagePayload("system", system));
		messages.add(new ChatMessagePayload("user", "Begin the run: the opening beat."));
		transcripts.put(sessionId, messages);
		return generate(sessionId, session, false);
	}

	// Apply the player's choice and ask the AI for the next beat.
	public StoryResponse choose(Long sessionId, String choiceText) {
		GameSession session = requireSession(sessionId);
		if (session.isGameOver()) {
			return terminal(session);
		}
		List<ChatMessagePayload> messages = transcripts.computeIfAbsent(sessionId, id -> {
			List<ChatMessagePayload> seed = new ArrayList<>();
			seed.add(new ChatMessagePayload("system", GameMasterPrompt.SYSTEM_PROMPT));
			return seed;
		});
		messages.add(new ChatMessagePayload("user",
				"The player chose: " + (choiceText == null ? "" : choiceText.trim())
						+ ". Continue the story and provide the next beat."));
		return generate(sessionId, session, true);
	}

	private StoryResponse generate(Long sessionId, GameSession session, boolean advance) {
		List<ChatMessagePayload> messages = transcripts.get(sessionId);
		String raw = aiProvider.ask(new AIProvider.ChatRequest(contextWindow(messages))).message();
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

	private List<ChatMessagePayload> contextWindow(List<ChatMessagePayload> messages) {
		if (messages.size() <= MAX_CONTEXT_MESSAGES) {
			return messages;
		}
		List<ChatMessagePayload> window = new ArrayList<>();
		window.add(messages.get(0)); // system prompt
		window.addAll(messages.subList(messages.size() - (MAX_CONTEXT_MESSAGES - 1), messages.size()));
		return window;
	}

	private StoryResponse terminal(GameSession session) {
		return new StoryResponse(session.getId(), "dense_forest", "", "This run has already ended.",
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
			return new Beat(location, npc, narrative, choices, hp, score, traits, items, outcome, ending);
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
	private record Beat(String location, String npc, String narrative, List<ChoiceView> choices, int hp, int score,
						List<TraitView> traits, List<String> items, String outcome, String ending) {
	}
}
