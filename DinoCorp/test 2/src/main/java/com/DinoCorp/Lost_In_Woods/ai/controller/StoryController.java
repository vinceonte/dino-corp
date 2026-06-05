package com.DinoCorp.Lost_In_Woods.ai.controller;

import com.DinoCorp.Lost_In_Woods.ai.dto.StoryChoiceRequest;
import com.DinoCorp.Lost_In_Woods.ai.dto.StoryResponse;
import com.DinoCorp.Lost_In_Woods.ai.dto.StoryStartRequest;
import com.DinoCorp.Lost_In_Woods.ai.service.StoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// AI-driven story endpoints. The LLM (via StoryService) controls the narrative
// and the choices; the game just relays the player's picks and tracks progress.
@RestController
@RequestMapping("/api/story")
@CrossOrigin(origins = "*")
public class StoryController {

	private final StoryService storyService;
	private final ObjectMapper json = new ObjectMapper();

	public StoryController(StoryService storyService) {
		this.storyService = storyService;
	}

	// Begin the AI playthrough for a session, with the chosen character (Epic 1).
	@PostMapping("/start")
	public ResponseEntity<StoryResponse> start(@RequestBody StoryStartRequest request) {
		return ResponseEntity.ok(storyService.start(request.sessionId(), describe(request), request.startingItems()));
	}

	// Submit the player's choice and get the next AI-generated beat.
	@PostMapping("/choose")
	public ResponseEntity<StoryResponse> choose(@RequestBody StoryChoiceRequest request) {
		return ResponseEntity.ok(storyService.choose(request.sessionId(), request.choice()));
	}

	// Streaming variants. Same inputs as /start and /choose, but the response is a
	// text/event-stream of:
	//   event: delta\ndata: "<chunk text>"\n\n        - one piece of narrative text
	//   event: done\ndata: <full StoryResponse JSON>  - final state, all fields filled
	//   event: error\ndata: "<message>"               - if generation fails
	// The frontend uses fetch() + ReadableStream to consume them and update the UI
	// progressively, so the player sees the narrative as it's being generated.
	@PostMapping(value = "/start/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<StreamingResponseBody> startStream(@RequestBody StoryStartRequest request) {
		return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(out -> stream(out,
				sink -> storyService.startStream(request.sessionId(), describe(request),
						request.startingItems(), sink)));
	}

	@PostMapping(value = "/choose/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<StreamingResponseBody> chooseStream(@RequestBody StoryChoiceRequest request) {
		return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(out -> stream(out,
				sink -> storyService.chooseStream(request.sessionId(), request.choice(), sink)));
	}

	// Run the generation, writing delta frames as they arrive and a final "done"
	// frame with the full StoryResponse. Errors become "error" frames so the
	// client can show a clear message instead of a half-stream.
	private void stream(OutputStream out, java.util.function.Function<java.util.function.Consumer<String>, StoryResponse> run) {
		try {
			StoryResponse resp = run.apply(text -> {
				try { writeFrame(out, "delta", json.writeValueAsString(text)); }
				catch (Exception ignored) { /* client gone — let main path detect */ }
			});
			writeFrame(out, "done", json.writeValueAsString(resp));
		} catch (Exception e) {
			try { writeFrame(out, "error", json.writeValueAsString(String.valueOf(e.getMessage()))); }
			catch (Exception ignored) { /* client gone */ }
		}
	}

	private void writeFrame(OutputStream out, String event, String dataJson) {
		try {
			out.write(("event: " + event + "\ndata: " + dataJson + "\n\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// Turn the appearance selections into a one-line character description for the AI
	// (or null if nothing was chosen).
	private String describe(StoryStartRequest r) {
		if (r == null) return null;
		if (notBlank(r.character())) return r.character().trim();
		boolean any = notBlank(r.gender()) || notBlank(r.hairColor())
				|| notBlank(r.skinColor()) || notBlank(r.clothingColor());
		if (!any) return null;
		String gender = notBlank(r.gender()) ? r.gender().toLowerCase() : "lone";
		return "a " + gender + " survivor with " + orUnknown(r.hairColor()) + " hair, "
				+ orUnknown(r.skinColor()) + " skin, wearing " + orUnknown(r.clothingColor()) + " clothing.";
	}

	private boolean notBlank(String s) { return s != null && !s.isBlank(); }
	private String orUnknown(String s) { return notBlank(s) ? s.toLowerCase() : "nondescript"; }
}
