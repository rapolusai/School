package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void idsAreVersion7AndSortByCreationTime() throws Exception {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();
        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(first.toString().compareTo(second.toString())).isNegative();
    }

    @Test
    void idsAreUnique() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).doesNotHaveDuplicates();
    }
}
