package androidx.databinding;

public class DataBinderMapperImpl extends MergedDataBinderMapper {
  DataBinderMapperImpl() {
    addMapper(new com.k1af.ft8af.DataBinderMapperImpl());
  }
}
