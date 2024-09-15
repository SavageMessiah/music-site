function filter(tags, text) {
    console.log("filter", tags, text);
    document.querySelectorAll(".track").forEach((el) => {
        el.style.display = "none";
    });
    var total = 0;
    var visible = 0;
    document.querySelectorAll(".track").forEach((el) => {
        const trackTags = el.getAttribute("data-tags").split(" ");
        const title = trackTitle = el.getAttribute("data-title").toLowerCase();
        total++;
        if(tags.every((t) => trackTags.includes("#" + t)) && title.includes(text)) {
            el.style.display = "block";
            visible++;
        }
    });
    document.querySelector("span#count").innerHTML = `Showing ${visible} out of ${total}.`
}

function updateSelection() {
    const tags = Array.from(document.querySelectorAll(".tagselect:checked")).map((el) => el.getAttribute("value"));
    const text = document.querySelector("#filter").value;
    filter(tags, text);
}

function clearSelection() {
    document.querySelectorAll(".tagselect:checked").forEach((el) => {
        el.checked = false;
    });
    document.querySelector("#filter").value = "";
    updateSelection();
}

window.onload = () => {
    console.log(new URLSearchParams(document.location));
    updateSelection();
    document.querySelectorAll(".tagselect").forEach((el) => {
        el.onchange = updateSelection;
    });
    document.querySelector("#filter").oninput = updateSelection;
    document.getElementById("clear").onclick = clearSelection;
};
