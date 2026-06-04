package com.DinoCorp.Lost_In_Woods.ai.controller;

import com.DinoCorp.Lost_In_Woods.ai.dto.StoryChoiceRequest;
import com.DinoCorp.Lost_In_Woods.ai.dto.StoryResponse;
import com.DinoCorp.Lost_In_Woods.ai.dto.StoryStartRequest;
import com.DinoCorp.Lost_In_Woods.ai.service.StoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// AI-driven story endpoints. The LLM (via StoryService) controls the narrative
// and the choices; the game just relays the player's picks and tracks progress.
@RestController
@RequestMapping("/api/story")
@CrossOrigin(origins = "*")
public class StoryController {

	private final StoryService storyService;

	public StoryController(StoryService storyService) {
		this.storyService = storyService;
	}

	// Begin the AI playthrough for a session, with the chosen character (Epic 1).
	@PostMapping("/start")
	public ResponseEntity<StoryResponse> start(@RequestBody StoryStartRequest request) {
		return ResponseEntity.ok(storyService.start(request.sessionId(), describe(request)));
	}

	// Turn the appearance selections into a one-line character description for the AI
	// (or null if nothing was chosen).
	private String describe(StoryStartRequest r) {
		if (r == null) {
			return null;
		}
		if (notBlank(r.character())) {
			return r.character().trim();
		}
		boolean any = notBlank(r.gender()) || notBlank(r.hairColor())
				|| notBlank(r.skinColor()) || notBlank(r.clothingColor());
		if (!any) {
			return null;
		}
		String gender = notBlank(r.gender()) ? r.gender().toLowerCase() : "lone";
		return "a " + gender + " survivor with " + orUnknown(r.hairColor()) + " hair, "
				+ orUnknown(r.skinColor()) + " skin, wearing " + orUnknown(r.clothingColor()) + " clothing.";
	}

	private boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private String orUnknown(String s) {
		return notBlank(s) ? s.toLowerCase() : "nondescript";
	}

	// Submit the player's choice and get the next AI-generated beat.
	@PostMapping("/choose")
	public ResponseEntity<StoryResponse> choose(@RequestBody StoryChoiceRequest request) {
		return ResponseEntity.ok(storyService.choose(request.sessionId(), request.choice()));
	}
}
