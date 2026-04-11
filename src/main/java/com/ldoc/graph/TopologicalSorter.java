package com.ldoc.graph;

import com.ldoc.model.MethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Produces a topological ordering of methods so that callees are always
 * processed before their callers. Cycles are broken by removing back-edges
 * (the cycle members are placed at the front so they receive best-effort summaries).
 */
public class TopologicalSorter {

    private static final Logger log = LoggerFactory.getLogger(TopologicalSorter.class);

    private final Map<String, MethodInfo> methodMap;

    public TopologicalSorter(Map<String, MethodInfo> methodMap) {
        this.methodMap = methodMap;
    }

    public List<String> sort() {
        // Kahn's algorithm
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>(); // callee -> callers

        for (String id : methodMap.keySet()) {
            inDegree.put(id, 0);
            dependents.put(id, new HashSet<>());
        }

        for (MethodInfo m : methodMap.values()) {
            for (String calleeId : m.getCalleeIds()) {
                if (methodMap.containsKey(calleeId)) {
                    inDegree.merge(m.getId(), 1, Integer::sum);
                    dependents.get(calleeId).add(m.getId());
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        List<String> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            ordered.add(current);
            for (String dependent : dependents.getOrDefault(current, Set.of())) {
                int deg = inDegree.merge(dependent, -1, Integer::sum);
                if (deg == 0) queue.add(dependent);
            }
        }

        // Handle cycle members (remaining nodes with inDegree > 0)
        Set<String> inCycle = new HashSet<>(methodMap.keySet());
        inCycle.removeAll(ordered);
        if (!inCycle.isEmpty()) {
            log.warn("Detected {} methods in cycles — adding with best-effort order", inCycle.size());
            ordered.addAll(0, inCycle); // put at front so they get processed first without callee context
        }

        return ordered;
    }
}
