package io.github.lingjiuu.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextFile {

    private Path path;

    private String content;
}
