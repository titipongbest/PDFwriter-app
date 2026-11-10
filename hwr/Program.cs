// Handwriting recognition helper for PDFWriter (Windows Ink, works offline).
// stdin:  first line = language ("th" / "en"); then "L" starts a text line, "S x,y x,y ..." is a stroke.
// stdout: one recognized line per text line, or "ERR <message>".
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Windows.Foundation;
using Windows.UI.Input.Inking;

static class Program
{
    static async Task<int> Main()
    {
        Console.InputEncoding = Encoding.UTF8;
        Console.OutputEncoding = Encoding.UTF8;
        try
        {
            var all = Console.In.ReadToEnd().Replace("\r", "").Split('\n');
            var lang = all.Length > 0 ? all[0].Trim() : "en";
            var lines = new List<List<List<Point>>>();
            foreach (var raw in all.Skip(1))
            {
                if (raw.StartsWith("L")) lines.Add(new List<List<Point>>());
                else if (raw.StartsWith("S ") && lines.Count > 0)
                {
                    var pts = raw.Substring(2).Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries)
                        .Select(p => p.Split(','))
                        .Select(p => new Point(double.Parse(p[0], CultureInfo.InvariantCulture), double.Parse(p[1], CultureInfo.InvariantCulture)))
                        .ToList();
                    if (pts.Count > 0) lines[lines.Count - 1].Add(pts);
                }
            }
            var container = new InkRecognizerContainer();
            var recognizers = container.GetRecognizers();
            string[] keys = lang == "th" ? new[] { "Thai", "ไทย" } : new[] { "English" };
            var rec = recognizers.FirstOrDefault(r => keys.Any(k => r.Name.IndexOf(k, StringComparison.OrdinalIgnoreCase) >= 0));
            if (rec == null)
            {
                var names = string.Join(", ", recognizers.Select(r => r.Name));
                Console.WriteLine("ERR NO_RECOGNIZER " + (names.Length > 0 ? names : "none"));
                return 2;
            }
            container.SetDefaultRecognizer(rec);
            var builder = new InkStrokeBuilder();
            foreach (var line in lines)
            {
                var strokes = new InkStrokeContainer();
                foreach (var s in line)
                {
                    var pts = s.Count == 1 ? new List<Point> { s[0], new Point(s[0].X + 0.5, s[0].Y + 0.5) } : s;
                    strokes.AddStroke(builder.CreateStroke(pts));
                }
                var results = await container.RecognizeAsync(strokes, InkRecognitionTarget.All);
                var text = string.Join(" ", results.Select(r => r.GetTextCandidates().FirstOrDefault() ?? ""));
                Console.WriteLine(text.Trim());
            }
            return 0;
        }
        catch (Exception e)
        {
            Console.WriteLine("ERR " + e.Message.Replace("\n", " "));
            return 1;
        }
    }
}
