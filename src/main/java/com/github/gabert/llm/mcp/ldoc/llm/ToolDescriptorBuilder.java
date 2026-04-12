package com.github.gabert.llm.mcp.ldoc.llm;

import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.ToolDescriptor;
import com.github.gabert.llm.mcp.ldoc.model.ToolParameter;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Hybrid builder for {@link ToolDescriptor}.
 *
 * Everything structural (coordinate, display name, language, parameter names and
 * native types, return type) is derived deterministically from the already-parsed
 * {@link MethodInfo}. The LLM contributes only the natural-language description
 * fields (top-level and per-parameter), which this class merges onto the skeleton.
 *
 * This avoids asking the LLM to re-derive facts we already know and eliminates the
 * risk of the LLM inventing, dropping, or renaming parameters.
 *
 * Also writes each parameter's description back onto the corresponding
 * {@link ParameterInfo} on the source {@link MethodInfo}, so downstream consumers
 * (storage, UI) see the descriptions in whatever view they prefer.
 */
public class ToolDescriptorBuilder {

    private static final Logger log = LoggerFactory.getLogger(ToolDescriptorBuilder.class);

    /**
     * Builds a descriptor if the method is public; returns {@code null} otherwise.
     * Side effect: also writes parameter descriptions from the response back onto
     * the method's {@link ParameterInfo} instances.
     */
    public ToolDescriptor build(MethodInfo method, LLMResponse response) {
        if (method.getVisibility() != Visibility.PUBLIC) {
            return null;
        }

        Map<String, String> paramDescs = response.parameterDescriptions() != null
                ? response.parameterDescriptions()
                : Collections.emptyMap();

        ToolDescriptor descriptor = new ToolDescriptor();
        descriptor.setCoordinate(method.getId());
        descriptor.setDisplayName(method.getClassName() + "." + method.getMethodName());
        descriptor.setLanguage("java");
        descriptor.setDescription(response.toolDescription() != null ? response.toolDescription() : "");
        descriptor.setReturnType(method.getReturnType());

        List<ToolParameter> toolParams = new ArrayList<>();
        List<ParameterInfo> methodParams = method.getParameters() != null
                ? method.getParameters()
                : Collections.emptyList();

        for (ParameterInfo p : methodParams) {
            String desc = paramDescs.get(p.getName());
            if (desc == null) {
                log.warn("LLM did not return description for parameter '{}' on {}",
                        p.getName(), method.getId());
                desc = "";
            }
            // Write description back onto the source ParameterInfo so the non-tool
            // views (Neo4j parameters column, UI param list) see it too.
            p.setDescription(desc);
            toolParams.add(new ToolParameter(p.getName(), p.getType(), desc));
        }
        descriptor.setParameters(toolParams);

        // Warn on any extra keys that did not match a real parameter
        for (String key : paramDescs.keySet()) {
            boolean matched = methodParams.stream().anyMatch(p -> p.getName().equals(key));
            if (!matched) {
                log.warn("LLM returned description for unknown parameter '{}' on {}",
                        key, method.getId());
            }
        }

        return descriptor;
    }
}
