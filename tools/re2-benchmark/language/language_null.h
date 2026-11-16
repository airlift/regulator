// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

// The null engine of the native RE2 runner, defined in language_null.cc. RE2 is a separate library reached by an
// out-of-line call, and the runner is built without link-time optimization, so defining the null in its own
// translation unit keeps the optimizer from seeing that it returns the length: each call stays a real call, as each
// call into RE2 does.

#include <cstddef>
#include <string_view>

size_t language_null_length(std::string_view input);
