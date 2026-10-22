package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.ApiException;
import java.util.regex.Pattern;

public final class Identifiers {
  private static final Pattern UUID=Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
  private Identifiers() {}

  public static boolean isUuid(String value) {
    return value!=null && UUID.matcher(value).matches();
  }

  public static void requireUuid(String value) {
    if(!isUuid(value)) throw new ApiException(400,"INVALID_ID","标识格式不正确");
  }
}
