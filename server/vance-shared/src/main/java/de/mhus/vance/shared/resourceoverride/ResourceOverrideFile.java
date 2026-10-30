package de.mhus.vance.shared.resourceoverride;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One file of an override version: {@code path} is relative to {@code _vance/<type>/}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceOverrideFile {

    private String path = "";

    private String content = "";
}
