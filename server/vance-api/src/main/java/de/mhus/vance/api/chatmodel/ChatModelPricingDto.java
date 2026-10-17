package de.mhus.vance.api.chatmodel;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** List price of a chat model per one million tokens, from the model catalog. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("chatmodel")
public class ChatModelPricingDto {

    /** ISO currency code, e.g. {@code USD}. */
    private String currency;

    private double inputPerMTok;

    private double outputPerMTok;
}
