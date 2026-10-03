package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.model.PageSelection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageSelectionTest {
  @Test
  void parsesRangesInSortedOrder() {
    assertEquals(List.of(1, 3, 4, 5), PageSelection.parse("5,1,3-4,3", 6).pages());
  }

  @Test
  void defaultsToAllPages() {
    assertEquals(List.of(1, 2, 3), PageSelection.parse(null, 3).pages());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "5-2", "4", "a", "1,", "1--2", "1.2"})
  void rejectsInvalidSelection(String value) {
    assertThrows(IllegalArgumentException.class, () -> PageSelection.parse(value, 3));
  }

  @Test
  void handlesWhitespaceAndEmptyDocuments() {
    assertEquals(List.of(1, 2, 3), PageSelection.parse(" 1, 2 - 3 ", 3).pages());
    assertEquals(List.of(), PageSelection.parse(null, 0).pages());
    assertThrows(IllegalArgumentException.class, () -> PageSelection.parse("1", 0));
    assertThrows(IllegalArgumentException.class, () -> PageSelection.parse(null, -1));
  }

  @Test
  void rejectsOverflowAndExposesImmutablePages() {
    assertThrows(IllegalArgumentException.class, () -> PageSelection.parse("9999999999999", 3));
    assertThrows(
        UnsupportedOperationException.class, () -> PageSelection.parse("1", 3).pages().add(2));
  }
}
