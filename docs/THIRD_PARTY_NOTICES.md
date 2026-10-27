# Third-party artwork

## Twemoji animal avatars

The twelve animal avatars bundled in `app/src/main/res/drawable/avatar_*.xml` derive from [Twemoji 14.0.2](https://github.com/twitter/twemoji/tree/v14.0.2/assets/svg), by Twitter, Inc. and other contributors, licensed under [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).

The original SVG shapes were converted to Android VectorDrawable resources, including equivalent circle/ellipse paths and rotation groups. The app adds pastel backgrounds separately. No Twemoji application code is included.

The full graphics license is bundled at [app/src/main/assets/licenses/twemoji.txt](../app/src/main/assets/licenses/twemoji.txt).

| Animal | Original SVG |
| :--- | :--- |
| Dog | `1f436.svg` |
| Fox | `1f98a.svg` |
| Panda | `1f43c.svg` |
| Frog | `1f438.svg` |
| Koala | `1f428.svg` |
| Monkey | `1f435.svg` |
| Lion | `1f981.svg` |
| Tiger | `1f42f.svg` |
| Rabbit | `1f430.svg` |
| Pig | `1f437.svg` |
| Cat | `1f431.svg` |
| Raccoon | `1f99d.svg` |

Avatars are selected locally from the numeric user ID. User names, tokens, and IDs are not sent to an avatar service.
