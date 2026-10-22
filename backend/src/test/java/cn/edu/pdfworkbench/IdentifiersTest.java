package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.util.Identifiers;
import cn.edu.pdfworkbench.tasks.TaskRuntime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IdentifiersTest {
  @Test void acceptsOnlyCanonicalLowercaseUuidStrings() {
    assertTrue(Identifiers.isUuid("01234567-89ab-cdef-0123-456789abcdef"));
    for(String value:new String[]{null,"","1-1-1-1-1","01234567-89AB-cdef-0123-456789abcdef",
        "../source.pdf","01234567-89ab-cdef-0123-456789abcdef/extra"," 01234567-89ab-cdef-0123-456789abcdef"}) {
      assertFalse(Identifiers.isUuid(value));
      var error=assertThrows(ApiException.class,()->Identifiers.requireUuid(value));
      assertEquals(400,error.status());assertEquals("INVALID_ID",error.code());
    }
    assertDoesNotThrow(()->Identifiers.requireUuid("00000000-0000-0000-0000-000000000000"));
  }

  @Test void taskTransportDoesNotRequireBusinessRepositoryInItsConstructor() {
    assertTrue(Arrays.stream(TaskRuntime.class.getConstructors())
        .flatMap(c->Arrays.stream(c.getParameterTypes()))
        .noneMatch(c->c.getPackageName().equals("cn.edu.pdfworkbench.ai")));
  }
}
