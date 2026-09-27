package com.example.travel.service;

import com.example.travel.graph.TravelState;
import com.example.travel.model.ReplanAction;
import com.example.travel.model.ReplanStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ReplanActionValidator {

    private static final Logger log = LoggerFactory.getLogger(ReplanActionValidator.class);

    public List<ReplanAction> resolve(
            ReplanStrategy strategy,
            TravelState state) {

        Set<ReplanAction> resolved = new LinkedHashSet<>();

        if (strategy == null) {
            return new ArrayList<>();
        }

        /*
         * Prefer explicitly resolved actions if present.
         */
        if (strategy.getResolvedActions() != null) {
            for (ReplanAction action : strategy.getResolvedActions()) {

                if (action != null && action.isAllowed(state)) {
                    resolved.add(action);
                } else if (action != null) {
                    log.info(
                            "Replan action {} rejected for current state",
                            action);
                }
            }
        }

        /*
         * Resolve LLM-provided wire names.
         */
        if (strategy.getActions() != null) {
            for (String token : strategy.getActions()) {

                ReplanAction.fromToken(token).ifPresent(action -> {

                    if (action.isAllowed(state)) {
                        resolved.add(action);
                    } else {
                        log.info(
                                "Replan action {} rejected for current state",
                                action);
                    }
                });
            }
        }

        return new ArrayList<>(resolved);
    }
}