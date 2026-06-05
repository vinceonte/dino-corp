package com.DinoCorp.Lost_In_Woods.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    // Send the site root straight to the game, so a bare link (e.g. a cloudflared
    // tunnel URL opened on a phone) lands on the splash screen instead of a 404/500.
    @GetMapping("/")
    public String home() {
        return "redirect:/lost_in_woods.html";
    }
}
