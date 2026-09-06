package com.example.datatransfer.core.flatten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PathParserTest {

    @Test
    void parse_plainAndIndexedKeys() {
        assertThat(PathParser.parse("user.name", "."))
                .containsExactly("user", "name");
        assertThat(PathParser.parse("user.tags[0]", "."))
                .containsExactly("user", "tags", 0);
        assertThat(PathParser.parse("items[2].price", "."))
                .containsExactly("items", 2, "price");
        assertThat(PathParser.parse("data[0][1]", "."))
                .containsExactly("data", 0, 1);
    }

    @Test
    void parsePattern_keepsWildcardSentinel() {
        assertThat(PathParser.parsePattern("items[*].price", "."))
                .containsExactly("items", PathParser.WILDCARD, "price");
        assertThat(PathParser.parsePattern("data[*].tags[*]", "."))
                .containsExactly("data", PathParser.WILDCARD, "tags", PathParser.WILDCARD);
    }

    @Test
    void toPath_roundTripsWithParse() {
        List<Object> segments = List.of("crmOrder", "lines", 1, "unitPrice");
        String path = PathParser.toPath(segments, ".");
        assertThat(path).isEqualTo("crmOrder.lines[1].unitPrice");
        assertThat(PathParser.parse(path, ".")).isEqualTo(segments);
    }

    @Test
    void toPath_joinsWildcardSentinel() {
        assertThat(PathParser.toPath(
                List.of("items", PathParser.WILDCARD, "price"), "."))
                .isEqualTo("items[*].price");
    }

    @Test
    void customSeparatorSupported() {
        assertThat(PathParser.parse("user/name", "/"))
                .containsExactly("user", "name");
    }

    @Test
    void parse_rejectsWildcardInActualKey() {
        assertThatThrownBy(() -> PathParser.parse("items[*].price", "."))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
