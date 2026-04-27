package com.gerkovger.hestia.control;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/webdav")
public class WebdavBrowserController {

    private final Path root;

    public WebdavBrowserController(
            @Value("${webdav.root:${user.home}/webdav}") String rootPath
    ) throws IOException {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
    }

    @GetMapping({"", "/", "/browse/**"})
    public String browse(HttpServletRequest request, Model model) throws IOException {
        String relativePath = extractPath(request, "/webdav/browse");
        Path current = safeResolve(relativePath);

        if (!Files.exists(current)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        if (!Files.isDirectory(current)) {
            return "redirect:/webdav/download/" + encodePath(root.relativize(current).toString());
        }

        List<FileEntry> entries = Files.list(current)
                .map(path -> new FileEntry(
                        path.getFileName().toString(),
                        Files.isDirectory(path),
                        root.relativize(path).toString().replace("\\", "/")
                ))
                .sorted(
                        Comparator.comparing(FileEntry::directory).reversed()
                                .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER)
                )
                .toList();

        Path parent = current.equals(root) ? null : root.relativize(current.getParent());

        model.addAttribute("entries", entries);
        model.addAttribute("currentPath", root.relativize(current).toString().replace("\\", "/"));
        model.addAttribute("parentPath", parent == null ? null : parent.toString().replace("\\", "/"));

        return "webdav-browser";
    }

    @GetMapping("/download/**")
    public ResponseEntity<Resource> download(HttpServletRequest request) throws IOException {
        String relativePath = extractPath(request, "/webdav/download");
        Path file = safeResolve(relativePath);

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        String filename = file.getFileName().toString();
        String contentType = Files.probeContentType(file);

        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(Files.size(file))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(resource);
    }

    @PostMapping("/download-selected")
    public ResponseEntity<StreamingResponseBody> downloadSelected(
            @RequestParam("selected") List<String> selected
    ) {
        StreamingResponseBody stream = outputStream -> {
            try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
                Set<Path> added = new java.util.HashSet<>();

                for (String item : selected) {
                    Path path = safeResolve(item);
                    if (!Files.exists(path)) {
                        continue;
                    }

                    if (Files.isRegularFile(path)) {
                        addFileToZip(zip, path, root.relativize(path), added);
                    } else if (Files.isDirectory(path)) {
                        try (var paths = Files.walk(path)) {
                            paths
                                    .filter(Files::isRegularFile)
                                    .forEach(file -> {
                                        try {
                                            addFileToZip(zip, file, root.relativize(file), added);
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                        } catch (RuntimeException e) {
                            if (e.getCause() instanceof IOException io) {
                                throw io;
                            }
                            throw e;
                        }
                    }
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"selected-files.zip\""
                )
                .body(stream);
    }

    private void addFileToZip(
            ZipOutputStream zip,
            Path file,
            Path relativePath,
            Set<Path> added
    ) throws IOException {

        Path normalized = relativePath.normalize();

        if (added.contains(normalized)) {
            return;
        }

        added.add(normalized);

        String zipPath = normalized.toString().replace("\\", "/");

        zip.putNextEntry(new ZipEntry(zipPath));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private Path safeResolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();

        if (!resolved.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        return resolved;
    }

    private String extractPath(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();

        if (!uri.startsWith(prefix)) {
            return "";
        }

        String path = uri.substring(prefix.length());

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        return java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    private String encodePath(String path) {
        return URLEncoder.encode(path.replace("\\", "/"), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    public record FileEntry(String name, boolean directory, String path) {}
}