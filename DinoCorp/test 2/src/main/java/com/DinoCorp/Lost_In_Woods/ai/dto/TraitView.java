package com.DinoCorp.Lost_In_Woods.ai.dto;

// An active trait the AI awarded the player. "bad" marks negative/flawed traits,
// which score differently (and can deduct on a fatal run).
public record TraitView(String name, boolean bad) {}
