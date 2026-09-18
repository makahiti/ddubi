"""실시간 실행기가 검증 통제 snapshot을 빠뜨리거나 I/O 실패를 통제 없음으로 바꾸는 회귀를 방어한다."""
from unittest.mock import Mock
import pytest
from streaming.segment_status import BLOCKAGE_SCHEMA
from streaming.segment_status_live import read_blockages


def test_verified_blockage_snapshot_uses_explicit_schema_and_failfast():
    spark=Mock()
    result=read_blockages(spark,'/verified/blockages')
    spark.read.schema.assert_called_once_with(BLOCKAGE_SCHEMA)
    spark.read.schema.return_value.option.assert_called_once_with('mode','FAILFAST')
    spark.read.schema.return_value.option.return_value.json.assert_called_once_with('/verified/blockages')
    assert result is spark.read.schema.return_value.option.return_value.json.return_value


def test_configured_input_failure_is_not_silently_no_closure():
    spark=Mock()
    assert read_blockages(spark,None) is None
    spark.read.schema.assert_not_called()
    spark.read.schema.return_value.option.return_value.json.side_effect=OSError('snapshot unavailable')
    with pytest.raises(OSError):read_blockages(spark,'/verified/blockages')
