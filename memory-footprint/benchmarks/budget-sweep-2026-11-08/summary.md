### 64 MiB

| Row | Language | Mode | Kind | r9g ratio | r8i ratio | Material | Flip vs comparator (est.) |
|---|---|---|---|---|---|---|---|
| curated/10-bounded-repeat/context | java | safe | target | 14.829 | 14.285 | yes | r9g: flips to slower than JDK (est. 4.35x its time); r8i: flips to slower than JDK (est. 3.31x its time) |
| curated/10-bounded-repeat/context | trino | safe | target | 14.666 | 14.153 | yes | r9g: flips to slower than Joni (est. 2.02x its time); r8i: flips to slower than Joni (est. 1.78x its time) |
| curated/10-bounded-repeat/context | java | native | target | 6.576 | 7.306 | yes | r9g: flips to slower than JDK (est. 1.22x its time); r8i: flips to slower than JDK (est. 1.09x its time) |
| curated/10-bounded-repeat/context | trino | native | target | 6.633 | 7.460 | yes | - |
| curated/10-bounded-repeat/context | re2 | native | target | 1.522 | 1.703 | yes | - |
| curated/10-bounded-repeat/context | rust | native | target | 1.451 | 1.607 | yes | - |
| curated/10-bounded-repeat/context | re2 | safe | target | 1.339 | 1.324 | yes | - |
| curated/10-bounded-repeat/context | rust | safe | target | 1.246 | 1.223 | yes | - |

### 48 MiB

| Row | Language | Mode | Kind | r9g ratio | r8i ratio | Material | Flip vs comparator (est.) |
|---|---|---|---|---|---|---|---|
| unicode/overlapping-words/english | trino | native | target | 338.130 | 1258.528 | yes | r9g: flips to slower than Joni (est. 7.14x its time); r8i: flips to slower than Joni (est. 38.41x its time) |
| unicode/overlapping-words/english | trino | safe | target | 335.406 | 1170.575 | yes | r9g: flips to slower than Joni (est. 9.89x its time); r8i: flips to slower than Joni (est. 47.10x its time) |
| unicode/overlapping-words/russian | trino | native | target | 294.322 | 887.867 | yes | r9g: flips to slower than Joni (est. 14.76x its time); r8i: flips to slower than Joni (est. 56.81x its time) |
| unicode/overlapping-words/russian | trino | safe | target | 295.874 | 863.774 | yes | r9g: flips to slower than Joni (est. 20.29x its time); r8i: flips to slower than Joni (est. 79.02x its time) |
| unicode/overlapping-words/russian | re2 | native | target | 163.960 | 178.036 | yes | r9g: flips to slower than RE2/J (est. 1.19x its time); r8i: flips to slower than RE2/J (est. 1.26x its time) |
| unicode/overlapping-words/russian | java | native | target | 163.247 | 176.139 | yes | r9g: flips to slower than JDK (est. 39.74x its time); r8i: flips to slower than JDK (est. 54.74x its time) |
| unicode/overlapping-words/russian | rust | native | target | 162.689 | 173.230 | yes | r9g: flips to slower than regex crate (est. 9.10x its time); r8i: flips to slower than regex crate (est. 7.65x its time) |
| unicode/overlapping-words/russian | re2 | safe | target | 158.637 | 164.283 | yes | r9g: flips to slower than RE2/J (est. 1.74x its time); r8i: flips to slower than RE2/J (est. 1.77x its time) |
| unicode/overlapping-words/russian | rust | safe | target | 159.559 | 160.407 | yes | r9g: flips to slower than regex crate (est. 13.39x its time); r8i: flips to slower than regex crate (est. 10.53x its time) |
| unicode/overlapping-words/russian | java | safe | target | 159.351 | 158.289 | yes | r9g: flips to slower than JDK (est. 58.05x its time); r8i: flips to slower than JDK (est. 74.91x its time) |
| unicode/overlapping-words/english | rust | native | target | 133.741 | 144.553 | yes | r9g: flips to slower than regex crate (est. 7.20x its time); r8i: flips to slower than regex crate (est. 5.63x its time) |
| unicode/overlapping-words/english | java | native | target | 133.751 | 143.064 | yes | r9g: flips to slower than JDK (est. 15.26x its time); r8i: flips to slower than JDK (est. 18.48x its time) |
| unicode/overlapping-words/english | re2 | safe | target | 135.711 | 135.510 | yes | r9g: flips to slower than RE2/J (est. 1.19x its time); r8i: flips to slower than RE2/J (est. 1.17x its time) |
| unicode/overlapping-words/english | java | safe | target | 131.596 | 133.038 | yes | r9g: flips to slower than JDK (est. 20.00x its time); r8i: flips to slower than JDK (est. 23.52x its time) |
| unicode/overlapping-words/english | rust | safe | target | 131.178 | 132.663 | yes | r9g: flips to slower than regex crate (est. 9.44x its time); r8i: flips to slower than regex crate (est. 7.02x its time) |
| curated/10-bounded-repeat/context | re2 | native | target | 17.923 | 20.460 | yes | r9g: flips to slower than RE2/J (est. 2.75x its time); r8i: flips to slower than RE2/J (est. 2.72x its time) |
| curated/10-bounded-repeat/context | rust | native | target | 17.474 | 20.251 | yes | r9g: flips to slower than regex crate (est. 2.77x its time); r8i: flips to slower than regex crate (est. 2.65x its time) |
| curated/10-bounded-repeat/context | java | native | target | 16.620 | 19.081 | yes | r9g: flips to slower than JDK (est. 3.07x its time); r8i: flips to slower than JDK (est. 2.84x its time) |
| curated/10-bounded-repeat/context | trino | native | target | 16.603 | 18.901 | yes | r9g: flips to slower than Joni (est. 1.44x its time); r8i: flips to slower than Joni (est. 1.51x its time) |
| curated/10-bounded-repeat/context | rust | safe | target | 15.505 | 15.058 | yes | r9g: flips to slower than regex crate (est. 4.27x its time); r8i: flips to slower than regex crate (est. 3.12x its time) |
| curated/10-bounded-repeat/context | re2 | safe | target | 15.283 | 14.948 | yes | r9g: flips to slower than RE2/J (est. 3.90x its time); r8i: flips to slower than RE2/J (est. 3.15x its time) |
| curated/10-bounded-repeat/context | java | safe | target | 14.922 | 14.482 | yes | r9g: flips to slower than JDK (est. 4.37x its time); r8i: flips to slower than JDK (est. 3.36x its time) |
| curated/10-bounded-repeat/context | trino | safe | target | 14.785 | 14.227 | yes | r9g: flips to slower than Joni (est. 2.04x its time); r8i: flips to slower than Joni (est. 1.79x its time) |
| unicode/overlapping-words/english | re2 | native | target | 136.386 | 146.379 | yes | - |

### 32 MiB

| Row | Language | Mode | Kind | r9g ratio | r8i ratio | Material | Flip vs comparator (est.) |
|---|---|---|---|---|---|---|---|
| unicode/overlapping-words/english | trino | native | target | 473.713 | 1680.462 | yes | r9g: flips to slower than Joni (est. 10.00x its time); r8i: flips to slower than Joni (est. 51.28x its time) |
| unicode/overlapping-words/english | trino | safe | target | 456.006 | 1560.944 | yes | r9g: flips to slower than Joni (est. 13.44x its time); r8i: flips to slower than Joni (est. 62.81x its time) |
| unicode/overlapping-words/russian | trino | native | target | 321.936 | 970.537 | yes | r9g: flips to slower than Joni (est. 16.15x its time); r8i: flips to slower than Joni (est. 62.10x its time) |
| unicode/overlapping-words/russian | trino | safe | target | 318.634 | 928.338 | yes | r9g: flips to slower than Joni (est. 21.85x its time); r8i: flips to slower than Joni (est. 84.92x its time) |
| unicode/overlapping-words/english | re2 | native | target | 199.369 | 212.879 | yes | r9g: flips to slower than RE2/J (est. 1.31x its time); r8i: flips to slower than RE2/J (est. 1.34x its time) |
| unicode/overlapping-words/english | rust | native | target | 198.880 | 208.822 | yes | r9g: flips to slower than regex crate (est. 10.70x its time); r8i: flips to slower than regex crate (est. 8.14x its time) |
| unicode/overlapping-words/english | java | native | target | 199.314 | 207.597 | yes | r9g: flips to slower than JDK (est. 22.74x its time); r8i: flips to slower than JDK (est. 26.82x its time) |
| unicode/overlapping-words/english | re2 | safe | target | 197.212 | 194.105 | yes | r9g: flips to slower than RE2/J (est. 1.73x its time); r8i: flips to slower than RE2/J (est. 1.68x its time) |
| unicode/overlapping-words/english | rust | safe | target | 194.592 | 191.976 | yes | r9g: flips to slower than regex crate (est. 14.00x its time); r8i: flips to slower than regex crate (est. 10.16x its time) |
| unicode/overlapping-words/english | java | safe | target | 194.054 | 193.585 | yes | r9g: flips to slower than JDK (est. 29.50x its time); r8i: flips to slower than JDK (est. 34.22x its time) |
| unicode/overlapping-words/russian | java | native | target | 177.062 | 193.643 | yes | r9g: flips to slower than JDK (est. 43.11x its time); r8i: flips to slower than JDK (est. 60.17x its time) |
| unicode/overlapping-words/russian | re2 | native | target | 177.335 | 191.496 | yes | r9g: flips to slower than RE2/J (est. 1.28x its time); r8i: flips to slower than RE2/J (est. 1.36x its time) |
| unicode/overlapping-words/russian | rust | native | target | 176.785 | 191.076 | yes | r9g: flips to slower than regex crate (est. 9.89x its time); r8i: flips to slower than regex crate (est. 8.43x its time) |
| unicode/overlapping-words/russian | re2 | safe | target | 173.266 | 182.738 | yes | r9g: flips to slower than RE2/J (est. 1.90x its time); r8i: flips to slower than RE2/J (est. 1.96x its time) |
| unicode/overlapping-words/russian | java | safe | target | 174.635 | 173.438 | yes | r9g: flips to slower than JDK (est. 63.62x its time); r8i: flips to slower than JDK (est. 82.08x its time) |
| unicode/overlapping-words/russian | rust | safe | target | 171.717 | 173.635 | yes | r9g: flips to slower than regex crate (est. 14.41x its time); r8i: flips to slower than regex crate (est. 11.40x its time) |
| curated/10-bounded-repeat/context | re2 | native | target | 17.503 | 20.510 | yes | r9g: flips to slower than RE2/J (est. 2.68x its time); r8i: flips to slower than RE2/J (est. 2.73x its time) |
| curated/10-bounded-repeat/context | rust | native | target | 17.556 | 20.464 | yes | r9g: flips to slower than regex crate (est. 2.78x its time); r8i: flips to slower than regex crate (est. 2.68x its time) |
| curated/10-bounded-repeat/context | java | native | target | 16.582 | 19.022 | yes | r9g: flips to slower than JDK (est. 3.07x its time); r8i: flips to slower than JDK (est. 2.83x its time) |
| curated/10-bounded-repeat/context | trino | native | target | 16.462 | 18.776 | yes | r9g: flips to slower than Joni (est. 1.43x its time); r8i: flips to slower than Joni (est. 1.50x its time) |
| curated/10-bounded-repeat/context | rust | safe | target | 15.617 | 15.075 | yes | r9g: flips to slower than regex crate (est. 4.30x its time); r8i: flips to slower than regex crate (est. 3.12x its time) |
| curated/10-bounded-repeat/context | re2 | safe | target | 15.550 | 15.060 | yes | r9g: flips to slower than RE2/J (est. 3.97x its time); r8i: flips to slower than RE2/J (est. 3.17x its time) |
| curated/10-bounded-repeat/context | java | safe | target | 14.909 | 14.542 | yes | r9g: flips to slower than JDK (est. 4.37x its time); r8i: flips to slower than JDK (est. 3.37x its time) |
| curated/10-bounded-repeat/context | trino | safe | target | 14.774 | 14.244 | yes | r9g: flips to slower than Joni (est. 2.04x its time); r8i: flips to slower than Joni (est. 1.79x its time) |

### 24 MiB

| Row | Language | Mode | Kind | r9g ratio | r8i ratio | Material | Flip vs comparator (est.) |
|---|---|---|---|---|---|---|---|
| curated/10-bounded-repeat/context | re2 | native | target | 17.383 | 20.389 | yes | r9g: flips to slower than RE2/J (est. 2.67x its time); r8i: flips to slower than RE2/J (est. 2.71x its time) |
| curated/10-bounded-repeat/context | rust | native | target | 17.763 | 20.248 | yes | r9g: flips to slower than regex crate (est. 2.82x its time); r8i: flips to slower than regex crate (est. 2.65x its time) |
| curated/10-bounded-repeat/context | java | native | target | 16.576 | 19.267 | yes | r9g: flips to slower than JDK (est. 3.07x its time); r8i: flips to slower than JDK (est. 2.87x its time) |
| curated/10-bounded-repeat/context | trino | native | target | 16.579 | 18.784 | yes | r9g: flips to slower than Joni (est. 1.44x its time); r8i: flips to slower than Joni (est. 1.50x its time) |
| curated/10-bounded-repeat/context | rust | safe | target | 15.741 | 15.207 | yes | r9g: flips to slower than regex crate (est. 4.34x its time); r8i: flips to slower than regex crate (est. 3.15x its time) |
| curated/10-bounded-repeat/context | re2 | safe | target | 15.436 | 14.821 | yes | r9g: flips to slower than RE2/J (est. 3.94x its time); r8i: flips to slower than RE2/J (est. 3.12x its time) |
| curated/10-bounded-repeat/context | java | safe | target | 14.907 | 14.431 | yes | r9g: flips to slower than JDK (est. 4.37x its time); r8i: flips to slower than JDK (est. 3.35x its time) |
| curated/10-bounded-repeat/context | trino | safe | target | 14.856 | 14.118 | yes | r9g: flips to slower than Joni (est. 2.05x its time); r8i: flips to slower than Joni (est. 1.78x its time) |
| wild/url/search | trino | safe | target | 1.051 | 1.064 | yes | - |

### 16 MiB

| Row | Language | Mode | Kind | r9g ratio | r8i ratio | Material | Flip vs comparator (est.) |
|---|---|---|---|---|---|---|---|
| curated/10-bounded-repeat/context | re2 | native | target | 17.570 | 20.434 | yes | r9g: flips to slower than RE2/J (est. 2.69x its time); r8i: flips to slower than RE2/J (est. 2.72x its time) |
| curated/10-bounded-repeat/context | rust | native | target | 17.714 | 20.114 | yes | r9g: flips to slower than regex crate (est. 2.81x its time); r8i: flips to slower than regex crate (est. 2.63x its time) |
| curated/10-bounded-repeat/context | trino | native | target | 16.618 | 19.024 | yes | r9g: flips to slower than Joni (est. 1.45x its time); r8i: flips to slower than Joni (est. 1.52x its time) |
| curated/10-bounded-repeat/context | java | native | target | 16.708 | 18.911 | yes | r9g: flips to slower than JDK (est. 3.09x its time); r8i: flips to slower than JDK (est. 2.82x its time) |
| curated/10-bounded-repeat/context | rust | safe | target | 15.634 | 15.039 | yes | r9g: flips to slower than regex crate (est. 4.31x its time); r8i: flips to slower than regex crate (est. 3.11x its time) |
| curated/10-bounded-repeat/context | re2 | safe | target | 15.413 | 15.045 | yes | r9g: flips to slower than RE2/J (est. 3.93x its time); r8i: flips to slower than RE2/J (est. 3.17x its time) |
| curated/10-bounded-repeat/context | java | safe | target | 14.925 | 14.272 | yes | r9g: flips to slower than JDK (est. 4.37x its time); r8i: flips to slower than JDK (est. 3.31x its time) |
| curated/10-bounded-repeat/context | trino | safe | target | 14.821 | 14.011 | yes | r9g: flips to slower than Joni (est. 2.05x its time); r8i: flips to slower than Joni (est. 1.76x its time) |
| wild/url/search | rust | safe | target | 7.017 | 7.171 | yes | r9g: flips to slower than regex crate (est. 1.71x its time); r8i: flips to slower than regex crate (est. 1.67x its time) |
| wild/url/search | re2 | safe | target | 7.003 | 6.961 | yes | r9g: flips to slower than RE2/J (est. 2.21x its time); r8i: flips to slower than RE2/J (est. 2.15x its time) |
| wild/url/search | java | safe | target | 6.123 | 6.084 | yes | - |
| dictionary/search/english-15 | re2 | native | target | 1.517 | 1.227 | yes | - |
| dictionary/search/english-15 | re2 | safe | target | 1.507 | 1.239 | yes | - |
| dictionary/search/english-15 | rust | safe | target | 1.507 | 1.224 | yes | - |
| dictionary/search/english-15 | rust | native | target | 1.504 | 1.227 | yes | - |
| dictionary/search/english-15 | java | native | target | 1.485 | 1.235 | yes | - |
| dictionary/search/english-15 | java | safe | target | 1.484 | 1.228 | yes | - |
| wild/url/search | re2 | native | target | 1.339 | 1.377 | yes | - |
| wild/url/search | rust | native | target | 1.336 | 1.375 | yes | - |
| wild/url/search | java | native | target | 1.313 | 1.370 | yes | - |
| wild/url/search | trino | safe | target | 1.097 | 1.114 | yes | - |
| wild/url/search | trino | native | target | 1.050 | 1.059 | yes | - |

### 8 MiB

| Row | Language | Mode | Kind | r9g ratio | r8i ratio | Material | Flip vs comparator (est.) |
|---|---|---|---|---|---|---|---|
| wild/url/search | rust | safe | target | 19.276 | 19.330 | yes | r9g: flips to slower than regex crate (est. 4.69x its time); r8i: flips to slower than regex crate (est. 4.51x its time) |
| wild/url/search | re2 | safe | target | 19.252 | 19.243 | yes | r9g: flips to slower than RE2/J (est. 6.09x its time); r8i: flips to slower than RE2/J (est. 5.95x its time) |
| wild/url/search | re2 | native | target | 11.714 | 13.145 | yes | r9g: flips to slower than RE2/J (est. 2.60x its time); r8i: flips to slower than RE2/J (est. 2.75x its time) |
| wild/url/search | rust | native | target | 11.614 | 13.028 | yes | r9g: flips to slower than regex crate (est. 1.98x its time); r8i: flips to slower than regex crate (est. 2.06x its time) |
| wild/url/search | java | safe | target | 20.482 | 20.707 | yes | - |
| wild/url/search | java | native | target | 12.231 | 13.620 | yes | - |
| curated/12-dictionary/single | re2 | native | target | 1.948 | 3.861 | yes | - |
| curated/12-dictionary/single | re2 | safe | target | 1.960 | 3.858 | yes | - |
| curated/12-dictionary/single | java | safe | target | 2.008 | 3.839 | yes | - |
| curated/12-dictionary/single | java | native | target | 2.108 | 3.831 | yes | - |
| curated/12-dictionary/single | rust | safe | target | 1.999 | 3.824 | yes | - |
| curated/12-dictionary/single | rust | native | target | 2.212 | 3.812 | yes | - |
| dictionary/search/english-15 | re2 | native | target | 1.527 | 1.231 | yes | - |
| dictionary/search/english-15 | rust | safe | target | 1.516 | 1.213 | yes | - |
| dictionary/search/english-15 | rust | native | target | 1.511 | 1.228 | yes | - |
| dictionary/search/english-15 | re2 | safe | target | 1.499 | 1.261 | yes | - |
| dictionary/search/english-15 | java | safe | target | 1.496 | 1.227 | yes | - |
| dictionary/search/english-15 | java | native | target | 1.487 | 1.243 | yes | - |
| wild/url/search | trino | safe | target | 1.268 | 1.305 | yes | - |
| wild/url/search | trino | native | target | 1.121 | 1.141 | yes | - |
| curated/12-dictionary/single | trino | native | target | 0.970 | 1.000 (n.s.) | yes | - |

