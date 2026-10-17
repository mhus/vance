package de.mhus.vance.api.chatmodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One selectable chat model, as offered by the model picker. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("chatmodel")
public class ChatModelDto {

    /** {@code providerInstance:modelName} — the value {@code //model} takes. */
    private String spec;

    private String providerInstance;

    private String modelName;

    /** {@code SMALL} or {@code LARGE}. */
    private String size;

    /** Whether the model honours a reasoning level other than {@code off}. */
    private boolean thinking;

    private int contextWindow;

    /** {@code null} when the catalog carries no price for the model. */
    private @Nullable ChatModelPricingDto pricing;
}
