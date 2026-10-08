package api.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PageQueryTest {

    @Test
    void appliesDefaultsWhenParametersAreMissing() {
        PageQuery query = PageQuery.of(null, null, 20, 100);

        Assertions.assertEquals(1, query.page());
        Assertions.assertEquals(20, query.pageSize());
    }

    @Test
    void rejectsInvalidPaginationParameters() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> PageQuery.of(0, 20, 20, 100));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PageQuery.of(1, 0, 20, 100));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PageQuery.of(1, 101, 20, 100));
    }
}
