// Local synthetic-data probe, authored for YEZVI stage 2 (2026-10-12).
// The upstream Ssentencepiece sources are compiled without changes.
#include "ssentencepiece/csrc/ssentencepiece.h"
#include <fstream>
#include <iomanip>
#include <map>
#include <sstream>

static std::string quote(const std::string &s) {
  std::ostringstream out;
  out << '"';
  for (unsigned char c : s) {
    if (c == '"' || c == '\\') out << '\\' << c;
    else if (c < 32) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << (int)c;
    else out << c;
  }
  out << '"';
  return out.str();
}

int main(int argc, char **argv) {
  if (argc != 4) return 2;
  ssentencepiece::Ssentencepiece encoder(argv[1], 1);
  std::ifstream tokens_file(argv[2]);
  std::map<int32_t, std::string> tokens;
  std::string piece;
  int32_t id;
  while (tokens_file >> piece >> id) tokens[id] = piece;
  std::ofstream output(argv[3], std::ios::binary);
  if (!tokens_file.eof() || !output || tokens.size() != 1025) return 3;
  std::vector<std::string> terms = {
      u8"YEZVI", u8"yezvi", u8"Kotlin", u8"Jetpack", u8"Compose",
      u8"Jetpack Compose", u8"Nalvero", u8"Névara", u8"Carpanedo",
      u8"Plaza", u8"de", u8"San", u8"Pedro", u8"Plaza de San Pedro",
      u8"Luna", u8"Tierra", u8"Luna Tierra", u8"中文", u8"Ne\u0301vara",
      u8"  Jetpack\t Compose  "};
  output << "[\n";
  for (size_t i = 0; i < terms.size(); ++i) {
    // Mirrors sherpa v1.13.8 EncodeHotwords' per-word istringstream handling
    // for inputs without the ':' score syntax or '/' stream delimiter.
    std::istringstream input(terms[i]);
    std::vector<std::string> pieces;
    std::vector<int32_t> ids;
    std::string word;
    while (input >> word) {
      std::vector<std::string> word_pieces;
      std::vector<int32_t> word_ids;
      encoder.Encode(word, &word_pieces);
      encoder.Encode(word, &word_ids);
      if (word_pieces.size() != word_ids.size()) return 4;
      for (size_t j = 0; j < word_ids.size(); ++j) {
        if (tokens.at(word_ids[j]) != word_pieces[j]) return 5;
      }
      pieces.insert(pieces.end(), word_pieces.begin(), word_pieces.end());
      ids.insert(ids.end(), word_ids.begin(), word_ids.end());
    }
    output << "  {\"term\":" << quote(terms[i]) << ",\"pieces\":[";
    for (size_t j = 0; j < pieces.size(); ++j) {
      if (j) output << ',';
      output << quote(pieces[j]);
    }
    output << "],\"ids\":[";
    bool unknown = false;
    for (size_t j = 0; j < ids.size(); ++j) {
      if (j) output << ',';
      output << ids[j];
      unknown = unknown || ids[j] == 0;
    }
    output << "],\"contains_unknown\":" << (unknown ? "true" : "false") << '}';
    if (i + 1 < terms.size()) output << ',';
    output << '\n';
  }
  output << "]\n";
  return output ? 0 : 6;
}
