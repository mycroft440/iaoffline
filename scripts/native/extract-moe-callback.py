"""Use the generated production callback verbatim in the scheduler integration test."""
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_text()
start = source.index('static bool iaoffline_moe_stream_mode()')
end = source.index('// Efficiency cores by CPU number', start)
Path(sys.argv[2]).write_text(source[start:end])
