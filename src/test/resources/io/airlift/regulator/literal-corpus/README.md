# OpenSubtitles samples

`en-sampled.txt.gzip`, `ru-sampled.txt.gzip`, and `zh-sampled.txt.gzip` are
rebar's `benchmarks/haystacks/opensubtitles/{en,ru,zh}-sampled.txt` from
[BurntSushi/rebar](https://github.com/BurntSushi/rebar) at commit
`463d00f31887e84c38467805b9e3122c314b9521`, stored gzip-compressed and
otherwise byte-identical. rebar derived them from the OPUS
[OpenSubtitles v2018](https://github.com/Helsinki-NLP/OPUS/tree/main/corpus/OpenSubtitles/v2018) corpus,
whose subtitles come from [OpenSubtitles](http://www.opensubtitles.org/). OPUS
states that the data carries the license of its original sources, and asks
users to cite:

P. Lison and J. Tiedemann, 2016, OpenSubtitles2016: Extracting Large Parallel
Corpora from Movie and TV Subtitles. In Proceedings of the 10th International
Conference on Language Resources and Evaluation (LREC 2016).

`tools/re2-benchmark/manifests/provision-literal-corpus.py` regenerates them
from a rebar checkout, and `tools/re2-benchmark/manifests/literal-corpus.tsv`
pins the revision and contents.
