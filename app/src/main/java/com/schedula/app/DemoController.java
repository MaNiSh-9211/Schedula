package com.schedula.app;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;

@Controller
@Profile("demo")
@RequestMapping("/demo")
public class DemoController {

    @GetMapping
    public String index(Model model) {
        model.addAttribute("pageTitle", "Schedula Demo");
        return "demo/index";
    }

    @GetMapping("/jobs")
    public String jobsView(Model model) {
        model.addAttribute("pageTitle", "Jobs");
        return "demo/jobs";
    }

    @GetMapping("/workflows")
    public String workflowsView(Model model) {
        model.addAttribute("pageTitle", "Workflows");
        return "demo/workflows";
    }

    @GetMapping("/schedules")
    public String schedulesView(Model model) {
        model.addAttribute("pageTitle", "Schedules");
        return "demo/schedules";
    }

    @GetMapping("/dlq")
    public String dlqView(Model model) {
        model.addAttribute("pageTitle", "Dead Letter Queue");
        return "demo/dlq";
    }

    @GetMapping("/analytics")
    public String analyticsView(Model model) {
        model.addAttribute("pageTitle", "Analytics");
        return "demo/analytics";
    }

    @GetMapping("/api-tester")
    public String apiTesterView(Model model) {
        model.addAttribute("pageTitle", "API Tester");
        return "demo/api-tester";
    }
}
