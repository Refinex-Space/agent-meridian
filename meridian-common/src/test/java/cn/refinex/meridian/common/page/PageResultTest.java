package cn.refinex.meridian.common.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResultTest {

    @Test
    void overfetchedRowIsDroppedAndProducesNextCursor() {
        PageRequest request = PageRequest.first(2);

        PageResult<String> page = PageResult.of(List.of("a", "b", "c"), request, Cursor::of);

        assertThat(page.data()).containsExactly("a", "b");
        assertThat(page.hasMore()).isTrue();
        assertThat(Cursor.decode(page.nextCursor()).keys()).containsExactly("b");
    }

    @Test
    void lastPageHasNoCursor() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), PageRequest.first(2), Cursor::of);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void mapKeepsPagination() {
        PageResult<String> page = PageResult.of(List.of("a", "b", "c"), PageRequest.first(2), Cursor::of);

        PageResult<Integer> mapped = page.map(String::length);

        assertThat(mapped.data()).containsExactly(1, 1);
        assertThat(mapped.nextCursor()).isEqualTo(page.nextCursor());
    }

    @Test
    void requestFromHttpParameters() {
        String after = Cursor.of("x").encode();

        assertThat(PageRequest.of(null, null)).isEqualTo(PageRequest.first(PageRequest.DEFAULT_LIMIT));
        assertThat(PageRequest.of(5, " ").after()).isNull();
        assertThat(PageRequest.of(5, after).after()).isEqualTo(Cursor.of("x"));
        assertThat(PageRequest.of(5, after).fetchSize()).isEqualTo(6);
        assertThatThrownBy(() -> PageRequest.of(0, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PageRequest.of(PageRequest.MAX_LIMIT + 1, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PageRequest.of(5, "garbage")).isInstanceOf(ValidationException.class);
    }
}
